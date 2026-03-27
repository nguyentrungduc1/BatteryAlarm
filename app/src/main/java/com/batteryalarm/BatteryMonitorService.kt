package com.batteryalarm

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_alarm_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_SETTINGS = "com.batteryalarm.UPDATE_SETTINGS"
        const val TAG = "BatteryMonitorService"

        // Repeat alert every 10 minutes (600_000 ms)
        const val ALERT_REPEAT_INTERVAL = 600_000L
        // Number of times to repeat the alert
        const val TTS_REPEAT_COUNT = 6
    }

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // Track last alert times to avoid spamming
    private var lastLowAlertTime = 0L
    private var lastFullAlertTime = 0L

    // Track current battery state
    private var currentBatteryLevel = -1
    private var isCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)

                currentBatteryLevel = if (scale > 0) (level * 100 / scale) else -1
                isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL

                Log.d(TAG, "Battery: $currentBatteryLevel%, charging: $isCharging")

                checkAndAlert()
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("battery_alarm_prefs", MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Đang theo dõi pin..."))
        initTts()
        registerBatteryReceiver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_SETTINGS) {
            Log.d(TAG, "Settings updated")
            // Reset timers so new settings take effect immediately
            lastLowAlertTime = 0L
            lastFullAlertTime = 0L
        }
        // START_STICKY: system will restart service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed — scheduling restart")
        tts?.stop()
        tts?.shutdown()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}

        // Schedule restart via broadcast
        val restartIntent = Intent("com.batteryalarm.RESTART_SERVICE")
        restartIntent.setPackage(packageName)
        sendBroadcast(restartIntent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — scheduling restart")
        val restartIntent = Intent("com.batteryalarm.RESTART_SERVICE")
        restartIntent.setPackage(packageName)
        sendBroadcast(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Battery Receiver ─────────────────────────────────────────────────────

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    // ── Check & Alert Logic ──────────────────────────────────────────────────

    private fun checkAndAlert() {
        if (currentBatteryLevel < 0) return

        val lowThreshold = prefs.getInt("low_threshold", 20)
        val fullThreshold = prefs.getInt("full_threshold", 90)
        val lowEnabled = prefs.getBoolean("low_alarm_enabled", true)
        val fullEnabled = prefs.getBoolean("full_alarm_enabled", true)
        val now = System.currentTimeMillis()

        // LOW ALARM: battery <= lowThreshold AND not charging
        if (lowEnabled && currentBatteryLevel <= lowThreshold && !isCharging) {
            if (now - lastLowAlertTime >= ALERT_REPEAT_INTERVAL) {
                lastLowAlertTime = now
                speakAlert("CẢNH BÁO MỨC PIN")
                Log.d(TAG, "Low battery alert at $currentBatteryLevel%")
            }
        }

        // FULL ALARM: battery >= fullThreshold AND still charging
        if (fullEnabled && currentBatteryLevel >= fullThreshold && isCharging) {
            if (now - lastFullAlertTime >= ALERT_REPEAT_INTERVAL) {
                lastFullAlertTime = now
                speakAlert("CẢNH BÁO MỨC PIN")
                Log.d(TAG, "Full battery alert at $currentBatteryLevel%")
            }
        }

        // Reset timers when conditions resolve
        if (isCharging && currentBatteryLevel > lowThreshold) {
            lastLowAlertTime = 0L // allow fresh alert next time it drops low
        }
        if (!isCharging) {
            lastFullAlertTime = 0L
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Try Vietnamese locale first
                val viLocale = Locale("vi", "VN")
                val result = tts!!.setLanguage(viLocale)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                if (!isTtsReady) {
                    // Fallback to default
                    tts!!.setLanguage(Locale.getDefault())
                    isTtsReady = true
                }

                // Configure audio attributes
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts!!.setAudioAttributes(audioAttrs)

                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                })

                Log.d(TAG, "TTS initialized. Vietnamese: $isTtsReady")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    private fun speakAlert(message: String) {
        if (!isTtsReady || tts == null) return

        tts!!.stop()

        // Queue the message TTS_REPEAT_COUNT times
        for (i in 1..TTS_REPEAT_COUNT) {
            val utteranceId = "battery_alert_$i"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val queueMode = if (i == 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts!!.speak(message, queueMode, params, utteranceId)
        }

        Log.d(TAG, "TTS speaking \"$message\" x$TTS_REPEAT_COUNT")
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Alarm Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh theo dõi pin nền"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Alarm")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val lowThreshold = prefs.getInt("low_threshold", 20)
        val fullThreshold = prefs.getInt("full_threshold", 90)
        val status = if (isCharging) "⚡ Đang sạc" else "🔋 Không sạc"
        val text = "Pin: $currentBatteryLevel% $status | Ngưỡng: thấp ${lowThreshold}% / đầy ${fullThreshold}%"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
