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
    private var formSubmitAttempts = 0
    private var jobDetailsWaitCount = 0

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

            val pkg = screenMap.packageName

            // 1. If currently inside JobAut dashboard/settings, do not automate or scroll
            if (pkg == "com.jobaut.app") {
                updateStatus(state = BotState.IDLE, lastLog = "Waiting for Jobstreet app to be opened...")
                delay(1500L)
                continue
            }

            // 2. If user is on home screen, launcher, or app switcher, do not interfere or press back
            if (isSystemOrLauncher(pkg)) {
                updateStatus(state = BotState.IDLE, lastLog = "Waiting for Jobstreet app in foreground...")
                delay(1500L)
                continue
            }

            // 3. If in another app / external webview
            if (!isJobstreet(pkg)) {
                val currentState = _statusFlow.value.state
                // Only navigate back if we were actively applying and Jobstreet redirected out to external site (Spec §4.2)
                if (currentJobHash.isNotEmpty() && (currentState == BotState.JOB_DETAILS || currentState == BotState.FILLING_FORM)) {
                    log("External application redirect detected ($pkg) - navigating back")
                    service.goBack()
                    currentJobHash = ""
                    formSubmitAttempts = 0
                    updateStatus(state = BotState.SCANNING_FEED, lastLog = "Skipped external redirect to $pkg")
                    delay(1500L)
                    continue
                } else {
                    // Otherwise simply pause and wait for Jobstreet without disturbing the user
                    updateStatus(state = BotState.IDLE, lastLog = "Waiting for Jobstreet (active: $pkg)...")
                    delay(1500L)
                    continue
                }
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
        formSubmitAttempts = 0
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
            formSubmitAttempts++
            if (formSubmitAttempts >= 3) {
                log("Form submission failed 3 times (validation error/CAPTCHA). Backing out...")
                formSubmitAttempts = 0
                service.goBack()
                delay(1000L)
                return
            }
            log("Tapping Submit Application (attempt $formSubmitAttempts)")
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
        jobDetailsWaitCount = 0
        updateStatus(state = BotState.JOB_DETAILS, lastLog = "Viewing job details")

        val applyBtn = findApplyButton(screenMap)

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
                navigateBackToFeed(service, screenMap)
                updateStatus(state = BotState.SCANNING_FEED)
            }
        } else {
            log("No apply button found in details, navigating back")
            navigateBackToFeed(service, screenMap)
            updateStatus(state = BotState.SCANNING_FEED)
        }
    }

    private suspend fun handleJobFeedScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        if (currentJobHash.isNotEmpty()) {
            jobDetailsWaitCount++
            if (jobDetailsWaitCount >= 4) {
                log("Job details screen did not open after 4 attempts, resetting to feed")
                currentJobHash = ""
                jobDetailsWaitCount = 0
            } else {
                log("Waiting for job details screen to open (attempt $jobDetailsWaitCount)...")
                delay(800L)
                return
            }
        } else {
            jobDetailsWaitCount = 0
        }

        updateStatus(state = BotState.SCANNING_FEED, lastLog = "Scanning job feed")

        // Find candidate job cards from the screen elements
        val jobCards = findJobCards(screenMap)
        var appliedOrClicked = false

        if (jobCards.isNotEmpty()) {
            log("Feed scan: evaluating ${jobCards.size} candidate jobs")
        }

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
                log("Multiple stalls detected in feed, scrolling up to refresh feed")
                service.scrollUp()
                consecutiveStallCount = 0
            }
        }
    }

    private fun navigateBackToFeed(service: JobstreetAccessibilityService, screenMap: ScreenMap) {
        // Prioritize in-app back/up button in the toolbar so we don't exit Jobstreet
        val backBtn = screenMap.elements.firstOrNull { elem ->
            (elem.isClickable || elem.className.contains("Button", ignoreCase = true)) && (
                elem.contentDescription.equals("Navigate up", ignoreCase = true) ||
                elem.contentDescription.equals("Back", ignoreCase = true) ||
                elem.text.equals("Back", ignoreCase = true) ||
                elem.contentDescription.equals("Close", ignoreCase = true) ||
                elem.text.equals("Close", ignoreCase = true)
            )
        }

        if (backBtn != null) {
            log("Tapping in-app back button: ${backBtn.label}")
            service.click(backBtn.node)
        } else if (currentJobHash.isNotEmpty()) {
            log("Navigating back to feed via global back")
            service.goBack()
        } else {
            log("Already on feed or no active job opened, skipping back to avoid exiting app")
        }
        currentJobHash = ""
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
        // Feed indicators that immediately disqualify a screen from being Job Details
        if (isFeedScreen(screenMap)) {
            return false
        }

        val hasApply = findApplyButton(screenMap) != null
        val lowerText = screenMap.rawText.lowercase()
        val hasJobDetailsKeywords = lowerText.contains("job description") ||
                lowerText.contains("about the role") ||
                lowerText.contains("about this role") ||
                lowerText.contains("key responsibilities") ||
                lowerText.contains("company overview") ||
                lowerText.contains("report this job") ||
                lowerText.contains("save job") ||
                lowerText.contains("posted ")

        return (currentJobHash.isNotEmpty() || hasJobDetailsKeywords) && hasApply && !isApplicationFormScreen(screenMap)
    }

    private fun isFeedScreen(screenMap: ScreenMap): Boolean {
        // 1. Presence of Jobstreet bottom navigation tabs
        val hasNavTabs = screenMap.elements.any { elem ->
            elem.isClickable && isFeedNavigationTab(elem.label.trim().lowercase())
        }

        // 2. Presence of search bar or query inputs
        val hasSearchBar = screenMap.elements.any { elem ->
            val l = elem.label.lowercase()
            (elem.isEditable || elem.isClickable) &&
                    (l.contains("search jobs") || l.contains("what:") || l.contains("where:") || l == "search")
        }

        // 3. Presence of multiple job cards on screen
        val cards = findJobCards(screenMap)
        if (cards.size >= 2) return true

        return hasNavTabs || hasSearchBar
    }

    private fun isFeedNavigationTab(lower: String): Boolean {
        return lower in setOf("search", "saved", "saved jobs", "applied", "applications", "my applications", "my activity", "profile") ||
                lower.startsWith("search\n") ||
                lower.startsWith("saved\n") ||
                lower.startsWith("applied\n") ||
                lower.startsWith("profile\n")
    }

    private fun isPureActionButton(lower: String): Boolean {
        return lower in setOf(
            "apply", "quick apply", "apply now", "continue", "submit", "submit application",
            "next", "review", "cancel", "done", "close", "maybe later", "filter", "filters"
        )
    }

    private fun findApplyButton(screenMap: ScreenMap): UIElement? {
        val candidates = screenMap.elements.filter { elem ->
            (elem.isClickable || elem.className.contains("Button", ignoreCase = true)) &&
                    elem.label.length <= 40 &&
                    !elem.label.contains("filter", ignoreCase = true)
        }

        // 1. Exact match on standard apply button phrases
        val exact = candidates.firstOrNull { elem ->
            val l = elem.label.trim().lowercase()
            l == "quick apply" ||
            l == "apply now" ||
            l == "apply" ||
            l == "apply on company site" ||
            l == "apply on employer site"
        }
        if (exact != null) return exact

        // 2. Word-boundary / phrase match
        return candidates.firstOrNull { elem ->
            val l = elem.label.trim().lowercase()
            (l.contains("quick apply") || l.contains("apply now") || l.startsWith("apply ")) &&
            !l.contains("application") &&
            !l.contains("applicant") &&
            !l.contains("applied")
        }
    }

    private fun findJobCards(screenMap: ScreenMap): List<UIElement> {
        return screenMap.elements.filter { elem ->
            isCandidateJobElement(elem)
        }
    }

    private fun isCandidateJobElement(elem: UIElement): Boolean {
        val label = elem.label.trim()
        val lower = label.lowercase()

        // Ignore empty or too short text (< 5 chars) or excessively long blocks (> 300 chars)
        if (label.length !in 5..300) return false

        // Must contain letters
        if (!label.any { it.isLetter() }) return false

        // Exclude bottom navigation bar tabs
        if (isFeedNavigationTab(lower)) return false

        // Exclude search bar, filter bar, sorting headers
        if (isSearchOrFilter(lower)) return false

        // Exclude action buttons (apply, done, close, etc.)
        if (isPureActionButton(lower)) return false

        // Exclude standalone metadata (salary, time, badges, employment types)
        if (isJobMetadata(lower, label)) return false

        return true
    }

    private fun isSearchOrFilter(lower: String): Boolean {
        return lower.startsWith("search") ||
                lower.contains("filter") ||
                lower.startsWith("sort by") ||
                lower.startsWith("what:") ||
                lower.startsWith("where:") ||
                lower == "clear all" ||
                lower == "all jobs" ||
                lower == "new to you"
    }

    private fun isJobMetadata(lower: String, rawLabel: String): Boolean {
        // Multi-line cards containing description/title + salary are legitimate cards
        if (rawLabel.lines().size >= 2 && rawLabel.length >= 35) {
            return false
        }
        if (lower.length < 3) return true
        val isSalary = lower.contains("₱") || lower.contains("php") || lower.contains("/mo") || lower.contains("/yr") || lower.contains("per month") || lower.contains("per year")
        val isTime = lower.endsWith("ago") || lower.contains("just posted")
        val isWorkType = lower in setOf("full-time", "full time", "part-time", "part time", "contract", "temporary", "internship", "permanent", "remote", "hybrid", "on-site")
        val isBadgeOrAction = lower in setOf("quick apply", "easily apply", "save", "save job", "share", "dismiss", "report", "promoted", "featured") ||
                lower.contains("applicant") || lower.contains("early applicant")
        return isSalary || isTime || isWorkType || isBadgeOrAction
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

    private fun isJobstreet(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val lower = pkg.lowercase()
        return lower.startsWith("com.jobstreet") ||
                lower.startsWith("com.seek") ||
                lower.contains("jobstreet")
    }

    private fun isSystemOrLauncher(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val lower = pkg.lowercase()
        return lower.contains("launcher") ||
                lower.contains("systemui") ||
                lower.contains("quickstep") ||
                lower.contains("recents") ||
                lower == "android" ||
                lower == "com.android.settings" ||
                lower.contains("googlequicksearchbox")
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
