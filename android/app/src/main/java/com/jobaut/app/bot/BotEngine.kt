package com.jobaut.app.bot

import android.content.Context
import android.util.Log
import com.jobaut.app.ai.LlmServerClient
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

    // Configured remote server URL
    private var configuredServerUrl: String = ""

    // In-memory applied/seen jobs cache for fast loop deduplication
    private val processedJobs = HashSet<String>()
    private var currentJobHash: String = ""
    private var consecutiveStallCount = 0
    private var formSubmitAttempts = 0
    private var jobDetailsWaitCount = 0

    // Search and tab selection state
    internal var switchedToNewToYou = false
    private var searchFormStallCount = 0

    /**
     * Starts the autonomous bot loop.
     * Runs strictly on [Dispatchers.Default].
     */
    fun start(
        serverUrl: String = "",
        qwenModelPath: String = "",
        rerankerModelPath: String = ""
    ) {
        if (loopJob?.isActive == true) {
            log("Bot is already running")
            return
        }

        loopJob = scope.launch(Dispatchers.Default) {
            try {
                switchedToNewToYou = false
                currentJobHash = ""
                consecutiveStallCount = 0
                formSubmitAttempts = 0
                jobDetailsWaitCount = 0

                val profile = configManager.loadProfile()
                configuredServerUrl = serverUrl.ifBlank { profile.serverUrl }

                updateStatus(
                    state = BotState.SCANNING_FEED,
                    isRunning = true,
                    lastLog = "Bot engine starting with server: $configuredServerUrl..."
                )

                // Probe and warmup models on server
                val conn = LlmServerClient.checkConnection(configuredServerUrl, profile.rerankerUrl)
                if (conn.isConnected) {
                    log("Connected to LLM Server: LLM=${conn.llmModel}, Reranker=${conn.rerankerModel}")
                } else {
                    log("LLM Server unreachable (${conn.message}), using rule-based reasoning fallback")
                }

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
     * Stops the autonomous bot loop.
     */
    fun stop() {
        log("Stopping bot engine...")
        loopJob?.cancel()
        loopJob = null
        switchedToNewToYou = false
    }

    private suspend fun cleanup() {
        switchedToNewToYou = false
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
                // Only navigate back if we were actively applying and Jobstreet redirected out to external site
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

            // Detect screen type & handle corresponding state.
            // IMPORTANT: Only isSearchResultsScreen browses and scrolls candidate jobs.
            // Any other landing screen triggers the search workflow instead of scrolling Home.
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

                // Search Form screen (search query entry form with Seek button)
                isSearchFormScreen(screenMap) -> {
                    handleSearchFormScreen(service, screenMap, userProfile)
                }

                // Search Results / Job Feed screen (ONLY genuine search results feed)
                isSearchResultsScreen(screenMap) -> {
                    handleJobFeedScreen(service, screenMap, userProfile)
                }

                // Home screen or any non-results screen: Click Search and type keyword
                else -> {
                    handleHomeScreen(service, screenMap, userProfile)
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

        if (doneBtn != null && doneBtn.node != null) {
            log("Verified confirmation button: '${doneBtn.label}'. Dismissing...")
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
            if (input.text.isBlank() && input.node != null) {
                val questionLabel = extractInputLabel(screenMap, input)
                val answer = QuestionReasoner.answerQuestion(
                    questionText = questionLabel,
                    profile = profile,
                    db = db,
                    serverUrl = configuredServerUrl
                )
                log("Verified input field '$questionLabel' -> typing answer '$answer'")
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
            val suggested = QuestionReasoner.answerQuestion(
                questionText = questionLabel,
                profile = profile,
                db = db,
                serverUrl = configuredServerUrl
            )
            val best = QuestionReasoner.selectBestOption(questionLabel, optionLabels, suggested)

            val matchedElem = checkableOptions.firstOrNull { it.label == best }
                ?: checkableOptions.firstOrNull()

            if (matchedElem != null && matchedElem.node != null) {
                log("Verified selection option: '${matchedElem.label}' for '$questionLabel'")
                service.click(matchedElem.node)
                delay(300L)
            }
        }

        // 3. Tap "Submit application" if present, otherwise "Continue" / "Next" / "Review"
        val submitBtn = screenMap.findButton("submit application")
            ?: screenMap.findButton("submit")

        if (submitBtn != null && submitBtn.node != null) {
            formSubmitAttempts++
            if (formSubmitAttempts >= 3) {
                log("Form submission repeated 3 times (validation error/CAPTCHA). Backing out...")
                formSubmitAttempts = 0
                service.goBack()
                delay(1000L)
                return
            }
            log("Verified Submit button. Tapping Submit Application (attempt $formSubmitAttempts)")
            service.click(submitBtn.node)
            delay(2000L)
            return
        }

        val advanceBtn = screenMap.findButton("continue")
            ?: screenMap.findButton("next")
            ?: screenMap.findButton("review")

        if (advanceBtn != null && advanceBtn.node != null) {
            log("Verified advance button: ${advanceBtn.label}. Tapping...")
            service.click(advanceBtn.node)
            delay(1500L)
            return
        }

        // If no progress button visible, scroll down to reveal more fields or buttons
        log("No action button found on form, scrolling down to reveal buttons")
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

        if (applyBtn != null && applyBtn.node != null) {
            // Verify if job should be applied
            val title = _statusFlow.value.currentJobTitle.ifBlank { extractJobTitle(screenMap) }
            val shouldApply = JobVerifier.shouldApply(
                title = title,
                company = "",
                description = screenMap.rawText.take(500),
                config = profile,
                serverUrl = configuredServerUrl
            )

            if (shouldApply) {
                log("Verified candidate fit for '$title'. Tapping '${applyBtn.label}' button...")
                updateStatus(currentJobTitle = title, state = BotState.FILLING_FORM)
                service.click(applyBtn.node)
            } else {
                log("Job did not pass qualification check, navigating back")
                navigateBackToFeed(service, screenMap)
                updateStatus(state = BotState.SCANNING_FEED)
            }
        } else {
            log("No apply button verified in job details, navigating back")
            navigateBackToFeed(service, screenMap)
            updateStatus(state = BotState.SCANNING_FEED)
        }
    }

    private suspend fun handleHomeScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        val targetKeyword = profile.targetTitles.firstOrNull()?.trim()?.ifEmpty { "junior python" } ?: "junior python"
        updateStatus(state = BotState.SCANNING_FEED, lastLog = "Home screen detected, initiating search for '$targetKeyword'")
        log("Home Screen detected: verifying search trigger for '$targetKeyword'")
        searchFormStallCount = 0

        // Verification 1: Look for top search bar / prompt
        val searchBar = findSearchBarElement(screenMap)
        if (searchBar != null && searchBar.node != null) {
            log("Verified top search bar ('${searchBar.label}'). Tapping to open search...")
            service.click(searchBar.node)
            delay(1500L)
            return
        }

        // Verification 2: Look for bottom navigation "Search" tab
        val bottomSearchTab = findBottomNavSearchTab(screenMap)
        if (bottomSearchTab != null && bottomSearchTab.node != null) {
            log("Verified bottom navigation Search tab ('${bottomSearchTab.label}'). Tapping to open search...")
            service.click(bottomSearchTab.node)
            delay(1500L)
            return
        }

        // Verification 3: Generic search prompt in upper screen
        val genericSearch = screenMap.elements.firstOrNull { elem ->
            val l = elem.label.lowercase().trim()
            (l.contains("search") || l.contains("find jobs")) && elem.centerY < 800
        }
        if (genericSearch != null && genericSearch.node != null) {
            log("Verified generic search prompt ('${genericSearch.label}'). Tapping...")
            service.click(genericSearch.node)
            delay(1500L)
            return
        }

        // Guard: NEVER scroll or evaluate jobs on Home! Wait for UI to stabilize.
        log("Waiting for search bar element on screen without scrolling...")
        delay(1200L)
    }

    private suspend fun handleSearchFormScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        val targetKeyword = profile.targetTitles.firstOrNull()?.trim()?.ifEmpty { "junior python" } ?: "junior python"
        updateStatus(state = BotState.SCANNING_FEED, lastLog = "Entering search keyword '$targetKeyword'")
        log("Search Form screen detected: verifying keyword input for '$targetKeyword'")

        // 1. Verify keyword input element exists
        val input = findKeywordInput(screenMap)
        if (input == null || input.node == null) {
            log("Keyword input element not found yet on search form, waiting...")
            delay(800L)
            return
        }

        val currentText = input.text.trim().lowercase()
        val keywordMatches = currentText.isNotEmpty() && currentText.contains(targetKeyword.lowercase())

        // 2. Check if keyword is already present and Seek button is ready
        val seekBtn = findSeekButton(screenMap)
        if (seekBtn != null && seekBtn.node != null && keywordMatches) {
            log("Verified keyword already matches ('$currentText'). Tapping '${seekBtn.label}' button...")
            service.click(seekBtn.node)
            switchedToNewToYou = false
            searchFormStallCount = 0
            delay(2000L)
            return
        }

        // 3. Clear existing text if Clear All button is present and text doesn't match
        val clearBtn = findClearAllButton(screenMap)
        if (clearBtn != null && clearBtn.node != null && currentText.isNotEmpty() && !keywordMatches) {
            log("Verified Clear all button. Clearing existing search text...")
            service.click(clearBtn.node)
            delay(400L)
        }

        // 4. Focus & enter keyword
        if (!keywordMatches) {
            log("Focusing keyword input in search form")
            service.click(input.node)
            delay(300L)

            log("Setting target keyword '$targetKeyword' into search form")
            service.setText(input.node, targetKeyword)
            delay(600L)
        }

        // 5. Verify the keyword was actually entered
        val afterInputScreen = service.getScreenMap()
        val freshSeekBtn = findSeekButton(afterInputScreen) ?: seekBtn
        if (freshSeekBtn != null && freshSeekBtn.node != null) {
            log("Verified Seek button ('${freshSeekBtn.label}'). Tapping to execute search...")
            service.click(freshSeekBtn.node)
            switchedToNewToYou = false
            searchFormStallCount = 0
            delay(2000L)
            return
        }

        searchFormStallCount++
        log("Seek button not immediately found (attempt: $searchFormStallCount)")

        // Action: Trigger IME Search / Enter action on the keyword input
        log("Attempting IME search action on keyword input")
        input.node.performAction(0x00010000 /* ACTION_IME_ACTION */)
        delay(1200L)

        val postImeScreen = service.getScreenMap()
        val postImeSeek = findSeekButton(postImeScreen)
        if (postImeSeek != null && postImeSeek.node != null) {
            log("Seek button verified after IME action; tapping '${postImeSeek.label}'")
            service.click(postImeSeek.node)
            switchedToNewToYou = false
            searchFormStallCount = 0
            delay(2000L)
            return
        }

        // Dismiss soft keyboard if covering the Seek button
        if (searchFormStallCount >= 2) {
            log("Dismissing keyboard to reveal Seek button")
            service.goBack()
            delay(800L)

            val postBackScreen = service.getScreenMap()
            val postBackSeek = findSeekButton(postBackScreen)
            if (postBackSeek != null && postBackSeek.node != null) {
                log("Seek button visible after keyboard dismissal; tapping '${postBackSeek.label}'")
                service.click(postBackSeek.node)
                switchedToNewToYou = false
                searchFormStallCount = 0
                delay(2000L)
                return
            }
        }
    }

    private suspend fun handleJobFeedScreen(
        service: JobstreetAccessibilityService,
        screenMap: ScreenMap,
        profile: UserProfile
    ) {
        searchFormStallCount = 0
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

        // 1. Switch to 'New to you' tab if visible and not already on it
        val newToYouTab = findNewToYouTab(screenMap)
        if (newToYouTab != null && newToYouTab.node != null && !switchedToNewToYou) {
            val isAlreadySelected = newToYouTab.node.isSelected ||
                    newToYouTab.isChecked ||
                    newToYouTab.contentDescription.contains("selected", ignoreCase = true)

            if (isAlreadySelected) {
                log("'New to you' tab is already selected")
                switchedToNewToYou = true
            } else {
                log("Verified 'New to you' tab: tapping to view freshest listings...")
                service.click(newToYouTab.node)
                switchedToNewToYou = true
                delay(1500L)
                return
            }
        }

        // 2. Check for "Strong match" / "Strong applicant" filter if present
        val strongFilter = findStrongMatchFilter(screenMap)
        if (strongFilter != null && strongFilter.node != null && !strongFilter.isChecked && !strongFilter.node.isSelected) {
            log("Found '${strongFilter.label}' filter chip: tapping to filter strongest matches...")
            service.click(strongFilter.node)
            delay(1500L)
            return
        }

        // 3. Find candidate job cards from the search results
        val jobCards = findJobCards(screenMap)
        var appliedOrClicked = false

        if (jobCards.isNotEmpty()) {
            log("Feed scan: evaluating ${jobCards.size} candidate jobs")
        }

        for (card in jobCards) {
            if (card.node == null) continue

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
                serverUrl = configuredServerUrl
            )

            processedJobs.add(jobHash)

            if (shouldApply) {
                log("Job verified match: '$title'. Opening details...")
                currentJobHash = jobHash
                updateStatus(currentJobTitle = title, state = BotState.JOB_DETAILS)
                service.click(card.node)
                appliedOrClicked = true
                consecutiveStallCount = 0
                break
            } else {
                log("Job rejected by verification: '$title'")
            }
        }

        if (!appliedOrClicked) {
            consecutiveStallCount++
            log("No matching job on screen, scrolling down in search feed (stall: $consecutiveStallCount)")
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

        if (backBtn != null && backBtn.node != null) {
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
        // Search Results, Home, and Search Form immediately disqualify a screen from being Job Details
        if (isSearchResultsScreen(screenMap) || isHomeScreen(screenMap) || isSearchFormScreen(screenMap)) {
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

    private fun extractInputLabel(screenMap: ScreenMap, input: UIElement): String {
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

        /**
         * Locates the search bar or search prompt on the Home Screen.
         */
        internal fun findSearchBarElement(screenMap: ScreenMap): UIElement? {
            // Guard against matching job cards: exclude multi-line or long labels (> 50 chars)
            val candidates = screenMap.elements.filter { elem ->
                elem.label.lines().size == 1 && elem.label.length <= 50
            }

            // Priority 1: Specific "continue your job search" element
            val continueSearch = candidates.firstOrNull { elem ->
                elem.label.contains("continue your job search", ignoreCase = true)
            }
            if (continueSearch != null) return continueSearch

            // Priority 2: Element with explicit search prompts
            val searchPrompt = candidates.firstOrNull { elem ->
                val l = elem.label.lowercase().trim()
                if (isFeedNavigationTab(l)) return@firstOrNull false
                l == "search jobs" ||
                l.startsWith("search jobs") ||
                l.contains("describe what you're looking for") ||
                l.contains("what: job title") ||
                l.contains("job title, keyword") ||
                (l == "search" && elem.centerY < 600 && (elem.isClickable || elem.isEditable))
            }
            if (searchPrompt != null) return searchPrompt

            // Priority 3: Editable input in the upper portion of the screen (not bottom nav)
            return candidates.firstOrNull { elem ->
                (elem.isEditable || elem.className.contains("EditText", ignoreCase = true)) &&
                elem.centerY < 700
            }
        }

        /**
         * Locates the 'Search' tab in the bottom navigation bar.
         */
        internal fun findBottomNavSearchTab(screenMap: ScreenMap): UIElement? {
            return screenMap.elements.firstOrNull { elem ->
                val l = elem.label.trim().lowercase()
                val isSearch = l == "search" || l.startsWith("search\n") || l.endsWith("\nsearch")
                isSearch && elem.centerY >= 1500
            }
        }

        /**
         * Locates the keyword input field on the search form.
         */
        internal fun findKeywordInput(screenMap: ScreenMap): UIElement? {
            // 1. Look for active EditText in search area (upper half of screen)
            val edit = screenMap.elements.firstOrNull { elem ->
                (elem.isEditable || elem.className.contains("EditText", ignoreCase = true)) &&
                elem.centerY < 1200
            }
            if (edit != null) return edit

            // 2. Look for search box placeholder / label
            return screenMap.elements.firstOrNull { elem ->
                val lbl = elem.label.lowercase()
                (lbl.contains("describe what you're looking for") ||
                 lbl.contains("job title, keyword") ||
                 lbl.contains("what: ")) &&
                elem.centerY < 1200
            }
        }

        /**
         * Locates the Seek / Search submission button.
         */
        internal fun findSeekButton(screenMap: ScreenMap): UIElement? {
            val seekElem = screenMap.elements.firstOrNull { elem ->
                val l = elem.label.trim().lowercase()
                if (l.contains("jobstreet") || l.contains("by seek") || l == "seek asia") return@firstOrNull false

                val isSeekMatch = l == "seek" || l.startsWith("seek ") ||
                        l.startsWith("seek(") || l.startsWith("seek (") || l.contains("seek jobs")
                isSeekMatch && elem.label.lines().size <= 3 && elem.label.length <= 35
            }
            if (seekElem != null) return seekElem

            val actionPhraseElem = screenMap.elements.firstOrNull { elem ->
                val l = elem.label.trim().lowercase()
                (l == "search jobs" || l.startsWith("search jobs ") || l == "find jobs") &&
                        elem.label.lines().size <= 2 && elem.label.length <= 30
            }
            if (actionPhraseElem != null) return actionPhraseElem

            return screenMap.elements.firstOrNull { elem ->
                val l = elem.label.trim().lowercase()
                val isSearchLabel = l == "search"
                val inSearchArea = elem.centerY < 1400
                val notBottomNav = !(isFeedNavigationTab(l) && elem.centerY >= 1500)
                isSearchLabel && inSearchArea && notBottomNav
            }
        }

        /**
         * Locates the Clear all button on the search form.
         */
        internal fun findClearAllButton(screenMap: ScreenMap): UIElement? {
            return screenMap.elements.firstOrNull { elem ->
                val l = elem.label.trim().lowercase()
                l == "clear all" || l.contains("clear all")
            }
        }

        /**
         * Locates the 'New to you' tab in the search results / feed.
         */
        internal fun findNewToYouTab(screenMap: ScreenMap): UIElement? {
            return screenMap.elements.firstOrNull { elem ->
                val lbl = elem.label.trim().lowercase()
                lbl.contains("new to you") && elem.centerY < 700
            }
        }

        /**
         * Locates a 'Strong match' or 'Strong applicant' quick filter chip if present.
         */
        internal fun findStrongMatchFilter(screenMap: ScreenMap): UIElement? {
            return screenMap.elements.firstOrNull { elem ->
                val l = elem.label.trim().lowercase()
                (l.contains("strong match") || l.contains("strong applicant") || l.contains("top match")) &&
                        elem.centerY < 800 && elem.label.lines().size <= 2
            }
        }

        internal fun isFeedNavigationTab(lower: String): Boolean {
            return lower in setOf("search", "saved", "saved jobs", "applied", "applications", "my applications", "my activity", "profile") ||
                    lower.startsWith("search\n") ||
                    lower.startsWith("saved\n") ||
                    lower.startsWith("applied\n") ||
                    lower.startsWith("profile\n") ||
                    lower.endsWith("\nsearch") ||
                    lower.endsWith("\nsaved") ||
                    lower.endsWith("\napplied") ||
                    lower.endsWith("\nprofile")
        }

        internal fun isPureActionButton(lower: String): Boolean {
            return lower in setOf(
                "apply", "quick apply", "apply now", "continue", "submit", "submit application",
                "next", "review", "cancel", "done", "close", "maybe later", "filter", "filters"
            )
        }

        internal fun findJobCards(screenMap: ScreenMap): List<UIElement> {
            return screenMap.elements.filter { elem ->
                isCandidateJobElement(elem)
            }
        }

        internal fun isCandidateJobElement(elem: UIElement): Boolean {
            val label = elem.label.trim()
            val lower = label.lowercase()

            if (label.length !in 5..300) return false
            if (!label.any { it.isLetter() }) return false
            if (isFeedNavigationTab(lower)) return false
            if (isSearchOrFilter(lower)) return false
            if (isPureActionButton(lower)) return false
            if (isJobMetadata(lower, label)) return false

            return true
        }

        internal fun isSearchOrFilter(lower: String): Boolean {
            return lower.startsWith("search") ||
                    lower.contains("filter") ||
                    lower.startsWith("sort by") ||
                    lower.startsWith("what:") ||
                    lower.startsWith("where:") ||
                    lower == "clear all" ||
                    lower.contains("all jobs") ||
                    lower.contains("new to you")
        }

        internal fun isJobMetadata(lower: String, rawLabel: String): Boolean {
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

        /**
         * Detects whether the current screen is genuinely the Search Results feed.
         */
        internal fun isSearchResultsScreen(screenMap: ScreenMap): Boolean {
            val lower = screenMap.rawText.lowercase()
            // Immediately disqualify if on search form, confirmation, or home prompt
            if (lower.contains("describe what you're looking for") || lower.contains("clear all")) return false
            if (lower.contains("continue your job search")) return false
            if (lower.contains("application submitted") || lower.contains("you've applied")) return false
            if (screenMap.findButton("submit application") != null) return false

            // Explicit search results markers
            if (lower.contains("search results") || lower.contains("results for") || lower.contains("jobs found")) return true
            if (findNewToYouTab(screenMap) != null) return true
            if (lower.contains("all jobs") && (lower.contains("filter") || lower.contains("sort"))) return true

            // Presence of filter button along with job cards in the results area
            val hasFilter = screenMap.elements.any { elem ->
                val l = elem.label.trim().lowercase()
                (l == "filter" || l == "filters" || l.startsWith("filter (") || l.startsWith("filters (") || l == "sort by") && elem.centerY < 900
            }
            val cards = findJobCards(screenMap)
            if (hasFilter && cards.isNotEmpty()) return true

            // Scrolled feed where cards are present and Home is ruled out
            return cards.size >= 2 && !isHomeScreen(screenMap)
        }

        /**
         * Detects whether the current screen is the Jobstreet Search Form screen.
         */
        internal fun isSearchFormScreen(screenMap: ScreenMap): Boolean {
            val lower = screenMap.rawText.lowercase()
            if (lower.contains("application submitted") || lower.contains("you've applied")) return false
            if (screenMap.findButton("submit application") != null) return false

            return lower.contains("describe what you're looking for") || lower.contains("clear all")
        }

        /**
         * Detects whether the current screen is the Jobstreet Home Screen.
         */
        internal fun isHomeScreen(screenMap: ScreenMap): Boolean {
            val lowerText = screenMap.rawText.lowercase()
            if (lowerText.contains("application submitted") || lowerText.contains("you've applied")) return false
            if (screenMap.findButton("submit application") != null) return false
            if (isSearchFormScreen(screenMap)) return false

            // Disqualify if feed markers are present
            if (lowerText.contains("search results")) return false
            if (lowerText.contains("all jobs")) return false
            if (findNewToYouTab(screenMap) != null) return false

            // Disqualify if job cards are present (scrolled feed) unless explicit home search prompt is present
            val cards = findJobCards(screenMap)
            if (cards.size >= 2 && !lowerText.contains("continue your job search")) {
                return false
            }

            // Rely on explicit home markers
            val hasExplicitHomeMarker = lowerText.contains("continue your job search") ||
                    lowerText.lines().any { it.trim().startsWith("hi ") } ||
                    (lowerText.contains("recommended") && !lowerText.contains("filter"))

            val searchBar = findSearchBarElement(screenMap)
            return (hasExplicitHomeMarker && searchBar != null) || lowerText.contains("continue your job search")
        }
    }
}
