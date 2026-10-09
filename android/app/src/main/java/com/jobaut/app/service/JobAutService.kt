package com.jobaut.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jobaut.app.R
import com.jobaut.app.bot.BotEngine
import com.jobaut.app.bot.BotStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service that keeps [BotEngine] active in the background when the user
 * navigates outside the app or switches to Jobstreet.
 *
 * Provides persistent notification with live application statistics and quick Stop action.
 */
class JobAutService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusObserverJob: Job? = null
    private var botEngine: BotEngine? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating JobAutService")
        createNotificationChannel()

        val engine = BotEngine(applicationContext)
        botEngine = engine
        _activeBotEngine.value = engine
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_STOP -> {
                handleStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val qwenPath = intent.getStringExtra(EXTRA_QWEN_PATH) ?: resolveDefaultModelPath(DEFAULT_QWEN_MODEL)
                val rerankerPath = intent.getStringExtra(EXTRA_RERANKER_PATH) ?: resolveDefaultModelPath(DEFAULT_RERANKER_MODEL)

                handleStart(qwenPath, rerankerPath)
                return START_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action: $action, ignoring")
                return START_NOT_STICKY
            }
        }
    }

    private fun handleStart(qwenPath: String, rerankerPath: String) {
        val engine = botEngine ?: run {
            val created = BotEngine(applicationContext)
            botEngine = created
            _activeBotEngine.value = created
            created
        }

        // Start foreground immediately with initial notification
        val initialNotification = buildNotification("Initializing bot engine...", 0)
        startForegroundCompat(initialNotification)
        isRunning = true
        _isRunningFlow.value = true

        // Observe BotEngine status updates to dynamically update notification
        statusObserverJob?.cancel()
        statusObserverJob = serviceScope.launch {
            engine.statusFlow.collect { status ->
                updateNotification(status)
            }
        }

        // Launch the engine
        engine.start(qwenModelPath = qwenPath, rerankerModelPath = rerankerPath)
    }

    private fun handleStop() {
        Log.i(TAG, "Stopping JobAutService...")
        statusObserverJob?.cancel()
        statusObserverJob = null

        botEngine?.stop()

        isRunning = false
        _isRunningFlow.value = false

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "JobAutService onDestroy")
        isRunning = false
        _isRunningFlow.value = false

        statusObserverJob?.cancel()
        statusObserverJob = null

        botEngine?.stop()
        botEngine = null
        _activeBotEngine.value = null

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.notification_channel_name)
            val channelDescription = getString(R.string.notification_channel_desc)
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDescription
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: BotStatus) {
        val detail = when {
            status.currentJobTitle.isNotBlank() -> status.currentJobTitle
            status.lastLog.isNotBlank() -> status.lastLog
            else -> getString(R.string.status_running)
        }

        val notification = buildNotification(detail, status.appliedToday)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(detailText: String, appliedToday: Int): Notification {
        // Tap action: open MainActivity
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action intent
        val stopIntent = Intent(this, JobAutService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = getString(R.string.status_applied_format, appliedToday, detailText)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop_bot),
                stopPendingIntent
            )
            .build()
    }

    private fun resolveDefaultModelPath(fileName: String): String {
        val modelsDir = File(filesDir, "models")
        val candidate = File(modelsDir, fileName)
        return if (candidate.exists()) candidate.absolutePath else ""
    }

    companion object {
        private const val TAG = "JobAutService"

        const val ACTION_START = "com.jobaut.app.action.START"
        const val ACTION_STOP = "com.jobaut.app.action.STOP"

        const val EXTRA_QWEN_PATH = "extra_qwen_path"
        const val EXTRA_RERANKER_PATH = "extra_reranker_path"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "jobaut_service_channel"

        private const val DEFAULT_QWEN_MODEL = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val DEFAULT_RERANKER_MODEL = "bge-reranker-v2-m3-q4_k_m.gguf"

        @Volatile
        var isRunning: Boolean = false
            private set

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _activeBotEngine = MutableStateFlow<BotEngine?>(null)
        val activeBotEngine: StateFlow<BotEngine?> = _activeBotEngine.asStateFlow()

        /**
         * Helper to start JobAutService in foreground.
         */
        @JvmStatic
        fun startService(
            context: Context,
            qwenPath: String = "",
            rerankerPath: String = ""
        ) {
            val intent = Intent(context, JobAutService::class.java).apply {
                action = ACTION_START
                if (qwenPath.isNotBlank()) putExtra(EXTRA_QWEN_PATH, qwenPath)
                if (rerankerPath.isNotBlank()) putExtra(EXTRA_RERANKER_PATH, rerankerPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Helper to stop JobAutService.
         */
        @JvmStatic
        fun stopService(context: Context) {
            val intent = Intent(context, JobAutService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
