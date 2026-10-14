package com.jobaut.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobaut.app.ai.LlmServerClient
import com.jobaut.app.automation.JobstreetAccessibilityService
import com.jobaut.app.data.AppDatabase
import com.jobaut.app.data.UserConfigManager
import com.jobaut.app.data.UserProfile
import com.jobaut.app.service.JobAutService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Log entry with timestamp, categorization tag, and message.
 */
data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String
)

/**
 * Unified UI State representing dashboard status, bot status, and user configuration.
 */
data class DashboardUiState(
    val isRunning: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val appliedToday: Int = 0,
    val currentJobTitle: String = "",
    val logs: List<LogEntry> = emptyList(),
    val profile: UserProfile = UserProfile(),
    val isJobstreetInstalled: Boolean = true,
    val serverUrl: String = "http://127.0.0.1:8080",
    val isServerConnected: Boolean = false,
    val loadedLlmModel: String = "",
    val loadedRerankerModel: String = "",
    val serverStatusMessage: String = "",
    val isCheckingServer: Boolean = false,
    // Kept for UI backward compatibility
    val qwenModelPresent: Boolean = false,
    val qwenModelSize: String = "",
    val rerankerModelPresent: Boolean = false,
    val rerankerModelSize: String = "",
    val isImportingModel: Boolean = false,
    val modelImportStatus: String = ""
)

/**
 * AndroidViewModel managing state and actions for the JobAut Jetpack Compose UI.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val configManager = UserConfigManager.getInstance(application)
    private val db = AppDatabase.getInstance(application)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var lastRecordedLogMessage: String = ""

    init {
        // Observe profile updates
        viewModelScope.launch {
            configManager.profileFlow.collectLatest { profile ->
                _uiState.update { it.copy(profile = profile, serverUrl = profile.serverUrl) }
            }
        }

        // Observe Foreground Service running state
        viewModelScope.launch {
            JobAutService.isRunningFlow.collectLatest { running ->
                _uiState.update { it.copy(isRunning = running) }
            }
        }

        // Observe active BotEngine status stream if service is running
        viewModelScope.launch {
            JobAutService.activeBotEngine.collectLatest { engine ->
                engine?.statusFlow?.collectLatest { status ->
                    _uiState.update { current ->
                        current.copy(
                            isRunning = status.isRunning,
                            appliedToday = status.appliedToday,
                            currentJobTitle = status.currentJobTitle
                        )
                    }

                    if (status.lastLog.isNotBlank() && status.lastLog != lastRecordedLogMessage) {
                        lastRecordedLogMessage = status.lastLog
                        val (tag, cleanMsg) = parseLogTag(status.lastLog)
                        addLog(tag, cleanMsg)
                    }
                }
            }
        }

        // Initial load of today's applied count from Room DB
        viewModelScope.launch {
            loadInitialAppliedCount()
        }

        // Check accessibility, package status and connect to server
        refreshAccessibilityStatus()
        checkJobstreetInstalled()

        viewModelScope.launch {
            val profile = configManager.loadProfile()
            checkServerConnection(profile.serverUrl, profile.rerankerUrl)
        }

        // Initial welcoming log
        addLog("INFO", "JobAut native dashboard initialized")
    }

    /**
     * Probes remote LLM and Reranker server and auto-loads models.
     */
    fun checkServerConnection(serverUrl: String = "", rerankerUrl: String = "") {
        viewModelScope.launch {
            val urlToTest = serverUrl.ifBlank { _uiState.value.serverUrl }
            _uiState.update { it.copy(isCheckingServer = true, serverStatusMessage = "Checking server connection...") }
            addLog("INFO", "Connecting to server at $urlToTest...")

            val status = LlmServerClient.checkConnection(urlToTest, rerankerUrl)
            _uiState.update {
                it.copy(
                    isCheckingServer = false,
                    isServerConnected = status.isConnected,
                    loadedLlmModel = status.llmModel,
                    loadedRerankerModel = status.rerankerModel,
                    serverStatusMessage = status.message
                )
            }

            if (status.isConnected) {
                addLog("SUCCESS", "Connected to AI server: LLM=${status.llmModel}, Reranker=${status.rerankerModel}")
            } else {
                addLog("WARN", "AI Server offline (${status.message}), using rule-based reasoning fallback")
            }
        }
    }

    /**
     * Updates and persists the server URL.
     */
    fun updateServerUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        viewModelScope.launch {
            configManager.saveServerUrl(trimmed)
            _uiState.update { it.copy(serverUrl = trimmed) }
            checkServerConnection(trimmed)
        }
    }

    /**
     * Legacy stubs for compatibility
     */
    fun refreshModelStatus() {
        checkServerConnection()
    }

    fun importModel(uri: Uri, isQwen: Boolean) {
        addLog("INFO", "External server is used. Model import is not required.")
    }

    fun deleteModel(isQwen: Boolean) {
        addLog("INFO", "External server is used.")
    }

    /**
     * Starts the autonomous JobAut background service and automatically opens Jobstreet.
     */
    fun startBot() {
        val context = getApplication<Application>()
        addLog("ACTION", "Starting JobAut bot background service...")

        viewModelScope.launch {
            val profile = configManager.loadProfile()
            val effectiveServer = profile.serverUrl.ifBlank { _uiState.value.serverUrl }

            JobAutService.startService(
                context = context,
                serverUrl = effectiveServer
            )

            // Transition directly to Jobstreet after service initialization
            delay(600L)
            launchJobstreet()
        }
    }

    /**
     * Stops the autonomous JobAut background service.
     */
    fun stopBot() {
        val context = getApplication<Application>()
        addLog("ACTION", "Stopping JobAut bot service...")
        JobAutService.stopService(context)
    }

    /**
     * Refreshes the accessibility service binding status.
     */
    fun refreshAccessibilityStatus() {
        val connected = JobstreetAccessibilityService.isConnected
        _uiState.update { it.copy(accessibilityGranted = connected) }
    }

    /**
     * Checks if Jobstreet or Seek app is installed on the device.
     */
    private fun checkJobstreetInstalled() {
        val pm = getApplication<Application>().packageManager
        val installed = (pm.getLaunchIntentForPackage("com.jobstreet.jobstreet") != null) ||
                (pm.getLaunchIntentForPackage("com.seek.jobstreet") != null)
        _uiState.update { it.copy(isJobstreetInstalled = installed) }
    }

    /**
     * Launches the Jobstreet application directly or falls back to Play Store.
     */
    fun launchJobstreet() {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage("com.jobstreet.jobstreet")
            ?: pm.getLaunchIntentForPackage("com.seek.jobstreet")

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            addLog("ACTION", "Launched Jobstreet app")
        } else {
            addLog("WARN", "Jobstreet app not installed, opening Play Store")
            try {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.jobstreet.jobstreet")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.jobstreet.jobstreet")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
        }
    }

    /**
     * Opens system Accessibility Settings screen directly.
     */
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
        addLog("ACTION", "Opened system accessibility settings")
    }

    /**
     * Appends a log entry to the internal circular log buffer (max 100 entries).
     */
    fun addLog(tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message
        )
        _uiState.update { current ->
            val updatedLogs = (listOf(entry) + current.logs).take(100)
            current.copy(logs = updatedLogs)
        }
    }

    /**
     * Clears all recorded UI log entries.
     */
    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
        addLog("INFO", "Logs cleared")
    }

    /**
     * Saves updated profile facts.
     */
    fun saveProfile(profile: UserProfile) {
        viewModelScope.launch {
            configManager.saveProfile(profile)
            _uiState.update { it.copy(profile = profile) }
            addLog("INFO", "User candidate profile updated")
        }
    }

    private suspend fun loadInitialAppliedCount() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val count = withContext(Dispatchers.IO) {
            db.appliedJobDao().getTodayCount(cal.timeInMillis)
        }
        _uiState.update { it.copy(appliedToday = count) }
    }

    private fun parseLogTag(log: String): Pair<String, String> {
        val clean = log.trim()
        return when {
            clean.startsWith("Error", ignoreCase = true) || clean.contains("failed", ignoreCase = true) ->
                "ERROR" to clean
            clean.startsWith("Warning", ignoreCase = true) || clean.contains("stall", ignoreCase = true) ->
                "WARN" to clean
            clean.startsWith("Tapping", ignoreCase = true) || clean.startsWith("Entering", ignoreCase = true) || clean.startsWith("Selecting", ignoreCase = true) ->
                "ACTION" to clean
            clean.startsWith("Job matches", ignoreCase = true) || clean.startsWith("Recorded application", ignoreCase = true) ->
                "SUCCESS" to clean
            clean.startsWith("Job rejected", ignoreCase = true) ->
                "FILTER" to clean
            else ->
                "INFO" to clean
        }
    }
}
