package com.jobaut.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobaut.app.automation.JobstreetAccessibilityService
import com.jobaut.app.data.AppDatabase
import com.jobaut.app.data.ModelManager
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
                _uiState.update { it.copy(profile = profile) }
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

        // Check accessibility, package status and model presence
        refreshAccessibilityStatus()
        checkJobstreetInstalled()
        refreshModelStatus()

        // Initial welcoming log
        addLog("INFO", "JobAut native dashboard initialized")
    }

    /**
     * Refreshes local model presence and file sizes.
     */
    fun refreshModelStatus() {
        val context = getApplication<Application>()
        val qwenPresent = ModelManager.isModelPresent(context, ModelManager.QWEN_MODEL_FILENAME)
        val qwenSize = ModelManager.getModelSizeFormatted(context, ModelManager.QWEN_MODEL_FILENAME)
        val rerankerPresent = ModelManager.isModelPresent(context, ModelManager.RERANKER_MODEL_FILENAME)
        val rerankerSize = ModelManager.getModelSizeFormatted(context, ModelManager.RERANKER_MODEL_FILENAME)

        _uiState.update {
            it.copy(
                qwenModelPresent = qwenPresent,
                qwenModelSize = qwenSize,
                rerankerModelPresent = rerankerPresent,
                rerankerModelSize = rerankerSize
            )
        }
    }

    /**
     * Imports a user-selected GGUF model file into app-private storage.
     */
    fun importModel(uri: Uri, isQwen: Boolean) {
        val context = getApplication<Application>()
        val filename = if (isQwen) ModelManager.QWEN_MODEL_FILENAME else ModelManager.RERANKER_MODEL_FILENAME
        val modelLabel = if (isQwen) "Qwen 2.5 0.5B" else "BGE Reranker"

        viewModelScope.launch {
            _uiState.update { it.copy(isImportingModel = true, modelImportStatus = "Importing $modelLabel...") }
            addLog("ACTION", "Importing $modelLabel model file...")

            val result = ModelManager.importModel(context, uri, filename)
            result.onSuccess { path ->
                addLog("INFO", "Successfully imported $modelLabel ($path)")
                if (isQwen) {
                    configManager.saveQwenModelPath(path)
                } else {
                    configManager.saveRerankerModelPath(path)
                }
                refreshModelStatus()
            }.onFailure { err ->
                addLog("ERROR", "Failed to import $modelLabel: ${err.message}")
            }

            _uiState.update { it.copy(isImportingModel = false, modelImportStatus = "") }
        }
    }

    /**
     * Deletes a previously imported model file from app storage.
     */
    fun deleteModel(isQwen: Boolean) {
        val context = getApplication<Application>()
        val filename = if (isQwen) ModelManager.QWEN_MODEL_FILENAME else ModelManager.RERANKER_MODEL_FILENAME
        ModelManager.deleteModel(context, filename)
        viewModelScope.launch {
            if (isQwen) {
                configManager.saveQwenModelPath("")
            } else {
                configManager.saveRerankerModelPath("")
            }
            refreshModelStatus()
        }
        addLog("INFO", "Removed $filename")
    }

    /**
     * Starts the autonomous JobAut background service and automatically opens Jobstreet.
     */
    fun startBot() {
        val context = getApplication<Application>()
        addLog("ACTION", "Starting JobAut bot background service...")

        viewModelScope.launch {
            val qwenPath = if (ModelManager.isModelPresent(context, ModelManager.QWEN_MODEL_FILENAME)) {
                ModelManager.getModelFile(context, ModelManager.QWEN_MODEL_FILENAME).absolutePath
            } else {
                configManager.getQwenModelPath()
            }

            val rerankerPath = if (ModelManager.isModelPresent(context, ModelManager.RERANKER_MODEL_FILENAME)) {
                ModelManager.getModelFile(context, ModelManager.RERANKER_MODEL_FILENAME).absolutePath
            } else {
                configManager.getRerankerModelPath()
            }

            JobAutService.startService(context, qwenPath, rerankerPath)

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
            addLog("ACTION", "Launched Jobstreet application")
        } else {
            addLog("ERROR", "Jobstreet is not installed. Opening Google Play...")
            val storeIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.jobstreet.jobstreet")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(storeIntent)
            } catch (e: Exception) {
                addLog("ERROR", "Failed opening Play Store: ${e.message}")
            }
        }
    }

    /**
     * Opens the Android Accessibility Settings screen for the user.
     */
    fun openAccessibilitySettings() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            addLog("ACTION", "Opened system Accessibility Settings")
        } catch (e: Exception) {
            addLog("ERROR", "Could not open Accessibility Settings: ${e.message}")
        }
    }

    /**
     * Persists updated [UserProfile] to Jetpack DataStore.
     */
    fun saveProfile(profile: UserProfile) {
        viewModelScope.launch {
            configManager.saveProfile(profile)
            addLog("SUCCESS", "Profile saved: ${profile.name} (${profile.targetTitles.size} target roles)")
        }
    }

    /**
     * Appends a log entry to the UI state. Keeps the last 500 entries to prevent memory growth.
     */
    fun addLog(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = LogEntry(timestamp = timestamp, tag = tag.uppercase(), message = message)
        _uiState.update { current ->
            val updated = (current.logs + entry).takeLast(500)
            current.copy(logs = updated)
        }
    }

    /**
     * Clears in-memory log list.
     */
    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
        addLog("INFO", "Log history cleared")
    }

    private suspend fun loadInitialAppliedCount() {
        val count = withContext(Dispatchers.IO) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            db.appliedJobDao().getTodayCount(cal.timeInMillis)
        }
        _uiState.update { it.copy(appliedToday = count) }
    }

    private fun parseLogTag(raw: String): Pair<String, String> {
        val tagRegex = Regex("^\\[?(ACTION|INPUT|LLM|SUCCESS|ERROR|INFO)\\]?:?\\s*(.*)", RegexOption.IGNORE_CASE)
        val match = tagRegex.find(raw)
        if (match != null) {
            val tag = match.groupValues[1].uppercase()
            val clean = match.groupValues[2].ifBlank { raw }
            return Pair(tag, clean)
        }

        val upper = raw.uppercase()
        val inferredTag = when {
            upper.contains("ERROR") || upper.contains("FAIL") || upper.contains("CRASH") -> "ERROR"
            upper.contains("SUCCESS") || upper.contains("APPLIED") -> "SUCCESS"
            upper.contains("LLM") || upper.contains("QWEN") || upper.contains("REASON") || upper.contains("RERANK") -> "LLM"
            upper.contains("INPUT") || upper.contains("TYP") || upper.contains("SET TEXT") -> "INPUT"
            upper.contains("ACTION") || upper.contains("CLICK") || upper.contains("SCROLL") || upper.contains("START") || upper.contains("STOP") -> "ACTION"
            else -> "INFO"
        }
        return Pair(inferredTag, raw)
    }
}
