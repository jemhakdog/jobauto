package com.jobaut.app.bot

import android.content.Context
import android.util.Log
import com.jobaut.app.ai.LlamaBridge
import com.jobaut.app.automation.JobstreetAccessibilityService
import com.jobaut.app.automation.ScreenMap
import com.jobaut.app.automation.UIElement
import com.jobaut.app.data.AppDatabase
import com.jobaut.app.data.AppliedJobEntity
import com.jobaut.app.data.UserConfigManager
import com.jobaut.app.data.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * State enum for BotEngine finite state machine.
 */
enum class BotState {
    IDLE,
    SCANNING_FEED,
    EVALUATING_JOB,
    JOB_DETAILS,
    FILLING_FORM,
    CONFIRMING,
    PAUSED,
    STOPPED
}

/**
 * Real-time operational status emitted by [BotEngine].
 */
data class BotStatus(
    val state: BotState = BotState.IDLE,
    val isRunning: Boolean = false,
    val appliedToday: Int = 0,
    val currentJobTitle: String = "",
    val lastLog: String = ""
)

/**
 * Autonomous automation engine executing the Jobstreet job discovery, filtering,
 * application flow, and question answering loop.
 */
class BotEngine(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val configManager = UserConfigManager.getInstance(context)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loopJob: Job? = null

    private val _statusFlow = MutableStateFlow(BotStatus())
    val statusFlow: StateFlow<BotStatus> = _statusFlow.asStateFlow()

    // Model contexts
    private var qwenCtxPtr: Long = 0L
    private var rerankerCtxPtr: Long = 0L

    // In-memory applied/seen jobs cache for fast loop deduplication
    private val processedJobs = HashSet<String>()
    private var currentJobHash: String = ""
    private var consecutiveStallCount = 0

    /**
     * Starts the autonomous bot loop.
     * Runs strictly on [Dispatchers.Default].
     */
    fun start(qwenModelPath: String = "", rerankerModelPath: String = "") {
        if (loopJob?.isActive == true) {
            log("Bot is already running")
            return
        }

        loopJob = scope.launch(Dispatchers.Default) {
            try {
                updateStatus(state = BotState.SCANNING_FEED, isRunning = true, lastLog = "Bot engine starting...")

                // Initialize native models if paths provided
                initModels(qwenModelPath, rerankerModelPath)

                // Refresh applied today count from Room DB
                val todayCount = getTodayAppliedCount()
                updateStatus(appliedToday = todayCount)

                log("Bot started. Daily count: $todayCount. Entering execution loop.")
                runLoop()
            } catch (e: CancellationException) {
                log("Bot engine cancelled cleanly")
            } catch (e: Throwable) {
                log("Bot engine error: ${e.message}")
                Log.e(TAG, "Unhandled error in BotEngine loop", e)
            } finally {
                withContext(NonCancellable) {
                    cleanup()
                    updateStatus(state = BotState.STOPPED, isRunning = false, lastLog = "Bot engine stopped")
                }
            }
        }
    }

    /**
     * Stops the autonomous bot loop and cleans up native model allocations.
     */
    fun stop() {
        log("Stopping bot engine...")
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun initModels(qwenPath: String, rerankerPath: String) {
        if (qwenPath.isNotBlank() && qwenCtxPtr == 0L) {
            log("Initializing Qwen model from $qwenPath")
            try {
                qwenCtxPtr = LlamaBridge.initModel(qwenPath, nThreads = 4, ctxSize = 1024)
                log("Qwen model initialized (ptr: $qwenCtxPtr)")
            } catch (e: Exception) {
                log("Failed initializing Qwen model: ${e.message}")
            }
        }

        if (rerankerPath.isNotBlank() && rerankerCtxPtr == 0L) {
            log("Initializing Reranker model from $rerankerPath")
            try {
                rerankerCtxPtr = LlamaBridge.initModel(rerankerPath, nThreads = 2, ctxSize = 512)
                log("Reranker model initialized (ptr: $rerankerCtxPtr)")
            } catch (e: Exception) {
                log("Failed initializing Reranker model: ${e.message}")
            }
        }
    }

    private suspend fun cleanup() {
        if (qwenCtxPtr != 0L) {
            val ptr = qwenCtxPtr
            qwenCtxPtr = 0L
            LlamaBridge.freeModel(ptr)
        }
        if (rerankerCtxPtr != 0L) {
            val ptr = rerankerCtxPtr
            rerankerCtxPtr = 0L
            LlamaBridge.freeModel(ptr)
        }
    }

    /**
     * Primary automation state machine loop.
     */
    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val service = JobstreetAccessibilityService.instance
            if (service == null) {
                updateStatus(state = BotState.PAUSED, lastLog = "Accessibility service not connected")
                delay(2000L)
                continue
            }

            // Fresh screen capture for each loop iteration (prevents leaking old ScreenMap)
            val screenMap = service.getScreenMap()
            if (screenMap.elements.isEmpty()) {
                delay(1000L)
                continue
            }

            val userProfile = configManager.loadProfile()

            // 1. Detect screen type & handle corresponding state
            when {
                // Confirmation screen (application submitted)
                isConfirmationScreen(screenMap) -> {
                    handleConfirmationScreen(service, screenMap)
                }

                // Application Form screen (input fields, selection options, submit/continue)
                isApplicationFormScreen(screenMap) -> {
                    handleApplicationFormScreen(service, screenMap, userProfile)
                }

                // Job Details screen (has "Apply", "Quick Apply", or "Apply now")
                isJobDetailsScreen(screenMap) -> {
                    handleJobDetailsScreen(service, screenMap, userProfile)
                }

                // Job Feed screen (job list / search results)
                else -> {
                    handleJobFeedScreen(service, screenMap, userProfile)
                }
            }

            // Safe UI transition delay
            delay(UI_TRANSITION_DELAY_MS)
        }
    }

    // --- Screen Handlers ---

    private suspend fun handleConfirmationScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap
    ) {
        updateStatus(state = BotState.CONFIRMING, lastLog = "Application submitted confirmation detected")

        val currentTitle = _statusFlow.value.currentJobTitle.ifBlank { "Job Application" }
        val jobId = currentJobHash.ifBlank { "job_${System.currentTimeMillis()}" }

        // Record applied job in Room DB
        try {
            val entity = AppliedJobEntity(
                jobId = jobId,
                title = currentTitle,
                company = "Jobstreet",
                appliedAt = System.currentTimeMillis()
            )
            db.appliedJobDao().insert(entity)
            log("Recorded application for '$currentTitle' (id: $jobId) in Room DB")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording applied job: ${e.message}")
        }

        // Increment today's count
        val todayCount = getTodayAppliedCount()
        updateStatus(appliedToday = todayCount)

        // Dismiss confirmation: Tap "Done", "Maybe later", or navigate back
        val doneBtn = screenMap.findButton("done")
            ?: screenMap.findButton("maybe later")
            ?: screenMap.findButton("close")

        if (doneBtn != null) {
            service.click(doneBtn.node)
        } else {
            service.goBack()
        }

        currentJobHash = ""
        updateStatus(currentJobTitle = "", state = BotState.SCANNING_FEED)
        consecutiveStallCount = 0
        delay(1500L)
    }

    private suspend fun handleApplicationFormScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        updateStatus(state = BotState.FILLING_FORM, lastLog = "Filling application form")

        // 1. Fill all empty text input fields on the screen
        val inputs = screenMap.findInputs()
        var filledAny = false
        for (input in inputs) {
            if (input.text.isBlank()) {
                val questionLabel = extractInputLabel(screenMap, input)
                val answer = QuestionReasoner.answerQuestion(questionLabel, profile, qwenCtxPtr, db)
                log("Filling input field '$questionLabel' -> '$answer'")
                val success = service.setText(input.node, answer)
                if (success) {
                    filledAny = true
                }
                delay(300L)
            }
        }
        if (filledAny) {
            delay(400L)
        }

        // 2. Handle radio / checkable options if present
        val checkableOptions = screenMap.filter { it.isCheckable && !it.isChecked }
        if (checkableOptions.isNotEmpty()) {
            val optionLabels = checkableOptions.map { it.label }
            val questionLabel = extractScreenQuestion(screenMap)
            val suggested = QuestionReasoner.answerQuestion(questionLabel, profile, qwenCtxPtr, db)
            val best = QuestionReasoner.selectBestOption(questionLabel, optionLabels, suggested)

            val matchedElem = checkableOptions.firstOrNull { it.label == best }
                ?: checkableOptions.firstOrNull()

            if (matchedElem != null) {
                log("Selecting option: '${matchedElem.label}' for '$questionLabel'")
                service.click(matchedElem.node)
                delay(300L)
            }
        }

        // 3. Tap "Submit application" if present, otherwise "Continue" / "Next" / "Review"
        val submitBtn = screenMap.findButton("submit application")
            ?: screenMap.findButton("submit")

        if (submitBtn != null) {
            log("Tapping Submit Application")
            service.click(submitBtn.node)
            delay(2000L)
            return
        }

        val advanceBtn = screenMap.findButton("continue")
            ?: screenMap.findButton("next")
            ?: screenMap.findButton("review")

        if (advanceBtn != null) {
            log("Tapping advance button: ${advanceBtn.label}")
            service.click(advanceBtn.node)
            delay(1500L)
            return
        }

        // If no progress button visible, scroll down to reveal more fields or buttons
        log("No action button found on form, scrolling down")
        service.scrollDown()
    }

    private suspend fun handleJobDetailsScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        updateStatus(state = BotState.JOB_DETAILS, lastLog = "Viewing job details")

        val applyBtn = screenMap.findButton("quick apply")
            ?: screenMap.findButton("apply now")
            ?: screenMap.findButton("apply")

        if (applyBtn != null) {
            // Verify if job should be applied
            val title = _statusFlow.value.currentJobTitle.ifBlank { extractJobTitle(screenMap) }
            val shouldApply = JobVerifier.shouldApply(
                title = title,
                company = "",
                description = screenMap.rawText.take(500),
                config = profile,
                rerankerCtxPtr = rerankerCtxPtr
            )

            if (shouldApply) {
                log("Tapping Apply button for '$title'")
                updateStatus(currentJobTitle = title, state = BotState.FILLING_FORM)
                service.click(applyBtn.node)
            } else {
                log("Job did not pass verification, navigating back")
                service.goBack()
                updateStatus(state = BotState.SCANNING_FEED)
            }
        } else {
            log("No apply button found in details, navigating back")
            service.goBack()
            updateStatus(state = BotState.SCANNING_FEED)
        }
    }

    private suspend fun handleJobFeedScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        updateStatus(state = BotState.SCANNING_FEED, lastLog = "Scanning job feed")

        // Find candidate job cards from the screen elements
        val jobCards = findJobCards(screenMap)
        var appliedOrClicked = false

        for (card in jobCards) {
            val title = card.label.lines().firstOrNull { it.isNotBlank() } ?: card.label
            val jobHash = card.label.trim()

            // Skip if already processed in this session
            if (processedJobs.contains(jobHash)) {
                continue
            }

            // Check if already in Room database
            val alreadyApplied = db.appliedJobDao().isApplied(jobHash)
            if (alreadyApplied) {
                processedJobs.add(jobHash)
                continue
            }

            // Evaluate job
            updateStatus(state = BotState.EVALUATING_JOB, currentJobTitle = title)
            val shouldApply = JobVerifier.shouldApply(
                title = title,
                company = "",
                description = card.label,
                config = profile,
                rerankerCtxPtr = rerankerCtxPtr
            )

            processedJobs.add(jobHash)

            if (shouldApply) {
                log("Job matches: '$title'. Opening details...")
                currentJobHash = jobHash
                updateStatus(currentJobTitle = title, state = BotState.JOB_DETAILS)
                service.click(card.node)
                appliedOrClicked = true
                consecutiveStallCount = 0
                break
            } else {
                log("Job rejected: '$title'")
            }
        }

        if (!appliedOrClicked) {
            consecutiveStallCount++
            log("No matching job on screen, scrolling down (stall: $consecutiveStallCount)")
            service.scrollDown()

            if (consecutiveStallCount >= MAX_CONSECUTIVE_STALLS) {
                log("Multiple stalls detected in feed, attempting back recovery")
                service.goBack()
                consecutiveStallCount = 0
            }
        }
    }

    // --- Screen Detection Helpers ---

    private fun isConfirmationScreen(screenMap: ScreenMap): Boolean {
        val lowerText = screenMap.rawText.lowercase()
        val confirmationKeywords = listOf(
            "application submitted",
            "you've applied",
            "applied successfully",
            "your application was sent",
            "application sent"
        )
        return confirmationKeywords.any { lowerText.contains(it) }
    }

    private fun isApplicationFormScreen(screenMap: ScreenMap): Boolean {
        val hasInputs = screenMap.findInputs().isNotEmpty()
        val hasRadioCheck = screenMap.filter { it.isCheckable }.isNotEmpty()
        val hasFormButtons = screenMap.findButton("submit application") != null ||
                screenMap.findButton("continue") != null ||
                screenMap.findButton("next") != null ||
                screenMap.findButton("review") != null

        return (hasInputs || hasRadioCheck || screenMap.rawText.contains("step ", ignoreCase = true)) && hasFormButtons
    }

    private fun isJobDetailsScreen(screenMap: ScreenMap): Boolean {
        val hasApply = screenMap.findButton("quick apply") != null ||
                screenMap.findButton("apply now") != null ||
                screenMap.findButton("apply") != null
        return hasApply && !isApplicationFormScreen(screenMap)
    }

    private fun findJobCards(screenMap: ScreenMap): List<UIElement> {
        return screenMap.filter { elem ->
            val label = elem.label
            // Job cards typically have substantial text (> 15 chars) and are clickable
            elem.isClickable && label.length > 15 &&
                    !label.contains("search", ignoreCase = true) &&
                    !label.contains("filter", ignoreCase = true) &&
                    !label.contains("continue", ignoreCase = true) &&
                    !label.contains("apply", ignoreCase = true)
        }
    }

    private fun extractInputLabel(screenMap: ScreenMap, input: UIElement): String {
        // Find nearest text element above this input element
        val candidates = screenMap.elements.filter { it != input && it.label.isNotBlank() && it.centerY < input.centerY }
        val nearest = candidates.minByOrNull { input.centerY - it.centerY }
        return nearest?.label ?: input.contentDescription.ifBlank { "Question" }
    }

    private fun extractScreenQuestion(screenMap: ScreenMap): String {
        val qElem = screenMap.elements.firstOrNull { it.label.contains("?") }
        if (qElem != null) return qElem.label
        val firstText = screenMap.elements.firstOrNull { it.label.length > 10 }
        return firstText?.label ?: screenMap.rawText.take(100)
    }

    private fun extractJobTitle(screenMap: ScreenMap): String {
        val firstHeader = screenMap.elements.firstOrNull {
            it.label.isNotBlank() && !it.isClickable && it.label.length in 5..80
        }
        return firstHeader?.label ?: "Job Title"
    }

    private suspend fun getTodayAppliedCount(): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return db.appliedJobDao().getTodayCount(cal.timeInMillis)
    }

    private fun updateStatus(
        state: BotState? = null,
        isRunning: Boolean? = null,
        appliedToday: Int? = null,
        currentJobTitle: String? = null,
        lastLog: String? = null
    ) {
        val current = _statusFlow.value
        _statusFlow.value = current.copy(
            state = state ?: current.state,
            isRunning = isRunning ?: current.isRunning,
            appliedToday = appliedToday ?: current.appliedToday,
            currentJobTitle = currentJobTitle ?: current.currentJobTitle,
            lastLog = lastLog ?: current.lastLog
        )
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        updateStatus(lastLog = message)
    }

    companion object {
        private const val TAG = "BotEngine"
        private const val UI_TRANSITION_DELAY_MS = 800L
        private const val MAX_CONSECUTIVE_STALLS = 6
    }
}
