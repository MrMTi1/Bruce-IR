package com.example.bruceir

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File

object WpaCrackerStatusManager {
    var isRunning: Boolean = false
    var currentPercent: Double = 0.0
    var currentTestedCount: Long = 0L
    var currentTotalCandidates: Long = 0L
    var currentSpeedHs: Long = 0L
    var currentWord: String = ""
    var sourceFileName: String = ""
    var onProgressUpdateListener: ((percent: Double, tested: Long, total: Long, speed: Long, word: String) -> Unit)? = null
}

class WpaCrackerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeCracker: WpaWordlistCracker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopCracking()
            return START_NOT_STICKY
        }

        val hcLine = intent?.getStringExtra(EXTRA_HC_LINE)
        val wordlistPath = intent?.getStringExtra(EXTRA_WORDLIST_PATH)
        val hcFileName = intent?.getStringExtra(EXTRA_HC_FILE_NAME) ?: "WPA_Capture"
        val useRules = intent?.getBooleanExtra(EXTRA_USE_RULES, true) ?: true
        val useTurbo = intent?.getBooleanExtra(EXTRA_USE_TURBO, true) ?: true
        val startFromCount = intent?.getLongExtra(EXTRA_START_FROM, 0L) ?: 0L

        if (hcLine == null || wordlistPath == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        startForegroundNotification()

        val wordlistFile = File(wordlistPath)
        val cracker = WpaWordlistCracker(
            context = this,
            hc22000Line = hcLine,
            wordlistFile = wordlistFile,
            sourceFileName = hcFileName,
            useBest64Rules = useRules,
            useTurboMultiCore = useTurbo,
            startFromCandidateCount = startFromCount,
            onProgress = { testedCount, totalCandidates, speedHs, currentWord ->
                val percent = if (totalCandidates > 0) ((testedCount * 100.0) / totalCandidates).coerceIn(0.0, 100.0) else 0.0
                val remainingCandidates = (totalCandidates - testedCount).coerceAtLeast(0)
                val etaSec = if (speedHs > 0) remainingCandidates / speedHs else 0L

                val etaText = if (etaSec > 0) {
                    val mins = etaSec / 60
                    val secs = etaSec % 60
                    if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                } else "..."

                WpaCrackerStatusManager.isRunning = true
                WpaCrackerStatusManager.currentPercent = percent
                WpaCrackerStatusManager.currentTestedCount = testedCount
                WpaCrackerStatusManager.currentTotalCandidates = totalCandidates
                WpaCrackerStatusManager.currentSpeedHs = speedHs
                WpaCrackerStatusManager.currentWord = currentWord
                WpaCrackerStatusManager.sourceFileName = hcFileName

                WpaCrackerStatusManager.onProgressUpdateListener?.invoke(percent, testedCount, totalCandidates, speedHs, currentWord)

                val progressInt = percent.toInt()
                val percentStr = String.format(java.util.Locale.US, "%.1f", percent)
                updateNotification(
                    "⚡ BruceIr WPA Cracker: $percentStr%",
                    "Szybkość: $speedHs H/s | ETA: ~$etaText | $testedCount/$totalCandidates",
                    progressInt
                )
            },
            onFound = { password ->
                WpaCrackerStatusManager.isRunning = false
                WpaCrackerStatusManager.onProgressUpdateListener = null
                releaseWakeLock()
                showResultNotification("🎉 BruceIr WPA Cracker - Znalaziono Hasło!", "Plik: $hcFileName\n🔑 Hasło: $password\n\nZapisano w potfile (http://bruceir.local)", true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            onDone = { found ->
                WpaCrackerStatusManager.isRunning = false
                WpaCrackerStatusManager.onProgressUpdateListener = null
                releaseWakeLock()
                if (!found) {
                    showResultNotification("❌ BruceIr WPA Cracker - Zakończono", "Przetestowano całą listę z pliku ${wordlistFile.name}. Nie znaleziono pasującego hasła.", false)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        )

        activeCracker = cracker
        cracker.start(serviceScope)

        return START_NOT_STICKY
    }

    private fun stopCracking() {
        WpaCrackerStatusManager.isRunning = false
        WpaCrackerStatusManager.onProgressUpdateListener = null
        activeCracker?.stop()
        activeCracker = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BruceIR:WpaCrackerWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // Max 24 hours
                }
            }
        } catch (e: Exception) {
            Log.e("WpaCrackerService", "WakeLock error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun startForegroundNotification() {
        createChannelIfNeeded()
        val notification = buildNotification("⚡ Łamanie WPA w tle...", "Inicjalizacja słownika...", 0)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(title: String, message: String, progressInt: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(title, message, progressInt))
    }

    private fun buildNotification(title: String, message: String, progressInt: Int): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, WpaCrackerService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(title)
            .setContentText(message)
            .setProgress(100, progressInt, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "ZATRZYMAJ (STOP)", stopPendingIntent)
            .build()
    }

    private fun showResultNotification(title: String, message: String, isSuccess: Boolean) {
        createChannelIfNeeded()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isSuccess) R.drawable.ic_wifi else android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(if (isSuccess) 9901 else 9902, builder.build())
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "WPA Cracker Service"
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "wpa_cracker_service_channel"
        const val NOTIFICATION_ID = 9900
        const val ACTION_STOP = "com.example.bruceir.STOP_CRACKING"
        const val EXTRA_HC_LINE = "hc_line"
        const val EXTRA_WORDLIST_PATH = "wordlist_path"
        const val EXTRA_HC_FILE_NAME = "hc_file_name"
        const val EXTRA_USE_RULES = "use_rules"
        const val EXTRA_USE_TURBO = "use_turbo"
        const val EXTRA_START_FROM = "start_from"

        fun startCracking(context: Context, hcLine: String, wordlistFile: File, hcFileName: String, useRules: Boolean, useTurbo: Boolean = true, startFrom: Long = 0L) {
            val intent = Intent(context, WpaCrackerService::class.java).apply {
                putExtra(EXTRA_HC_LINE, hcLine)
                putExtra(EXTRA_WORDLIST_PATH, wordlistFile.absolutePath)
                putExtra(EXTRA_HC_FILE_NAME, hcFileName)
                putExtra(EXTRA_USE_RULES, useRules)
                putExtra(EXTRA_USE_TURBO, useTurbo)
                putExtra(EXTRA_START_FROM, startFrom)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCracking(context: Context) {
            val intent = Intent(context, WpaCrackerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
