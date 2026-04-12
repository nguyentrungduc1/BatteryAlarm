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
        const val ACTION_ALARM_TICK = "com.batteryalarm.ALARM_TICK"
        const val TAG = "BatteryMonitorService"

        // Repeat alert every 10 minutes
        const val ALERT_REPEAT_INTERVAL = 600_000L
        // Number of times to repeat the TTS message
        const val TTS_REPEAT_COUNT = 6

        // SharedPreferences keys for persistent timers
        const val PREF_LAST_LOW_ALERT = "last_low_alert_time"
        const val PREF_LAST_FULL_ALERT = "last_full_alert_time"

        // AlarmManager request codes
        const val ALARM_REQUEST_LOW = 1001
        const val ALARM_REQUEST_FULL = 1002
    }

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // WakeLock to keep CPU alive when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    // Track current battery state
    private var currentBatteryLevel = -1
    private var isCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                currentBatteryLevel = if (scale > 0) (level * 100 / scale) else -1
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                Log.d(TAG, "Battery: $currentBatteryLevel%, charging: $isCharging")

                checkAndAlert()
                updateNotification()
            }
        }
    }

    // Receives AlarmManager ticks to re-check and re-alert after 10 minutes
    private val alarmTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "AlarmManager tick received")
            refreshBatteryState()
            checkAndAlert()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("battery_alarm_prefs", MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Đang theo dõi pin..."))
        acquireWakeLock()
        initTts()
        registerBatteryReceiver()
        registerAlarmTickReceiver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Settings updated - reset timers")
                prefs.edit()
                    .putLong(PREF_LAST_LOW_ALERT, 0L)
                    .putLong(PREF_LAST_FULL_ALERT, 0L)
                    .apply()
                cancelAlarms()
            }
            ACTION_ALARM_TICK -> {
                Log.d(TAG, "Alarm tick via onStartCommand")
                refreshBatteryState()
                checkAndAlert()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed - scheduling restart")
        tts?.stop()
        tts?.shutdown()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(alarmTickReceiver) } catch (_: Exception) {}
        releaseWakeLock()

        val restartIntent = Intent("com.batteryalarm.RESTART_SERVICE")
        restartIntent.setPackage(packageName)
        sendBroadcast(restartIntent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - scheduling restart")
        val restartIntent = Intent("com.batteryalarm.RESTART_SERVICE")
        restartIntent.setPackage(packageName)
        sendBroadcast(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // WakeLock

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BatteryAlarm::ServiceWakeLock"
        )
        wakeLock?.acquire()
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    // Receivers

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun registerAlarmTickReceiver() {
        val filter = IntentFilter(ACTION_ALARM_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmTickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmTickReceiver, filter)
        }
    }

    // Read current battery state directly (for AlarmManager ticks)
    private fun refreshBatteryState() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        currentBatteryLevel = if (scale > 0) (level * 100 / scale) else -1
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        Log.d(TAG, "Refreshed: $currentBatteryLevel%, charging: $isCharging")
    }

    // Check & Alert

    private fun checkAndAlert() {
        if (currentBatteryLevel < 0) return

        val lowThreshold = prefs.getInt("low_threshold", 20)
        val fullThreshold = prefs.getInt("full_threshold", 90)
        val lowEnabled = prefs.getBoolean("low_alarm_enabled", true)
        val fullEnabled = prefs.getBoolean("full_alarm_enabled", true)
        val now = System.currentTimeMillis()

        // Read persistent timers - survive service restarts
        val lastLowAlertTime = prefs.getLong(PREF_LAST_LOW_ALERT, 0L)
        val lastFullAlertTime = prefs.getLong(PREF_LAST_FULL_ALERT, 0L)

        // Check Do Not Disturb
        val dndActive = isInDndPeriod()

        // LOW ALARM
        if (lowEnabled && currentBatteryLevel <= lowThreshold && !isCharging) {
            if (!dndActive && now - lastLowAlertTime >= ALERT_REPEAT_INTERVAL) {
                prefs.edit().putLong(PREF_LAST_LOW_ALERT, now).apply()
                speakAlert("CẢNH BÁO MỨC PIN")
                scheduleNextAlarm(ALARM_REQUEST_LOW, ALERT_REPEAT_INTERVAL)
                Log.d(TAG, "Low battery alert at $currentBatteryLevel%")
            }
        } else {
            // Condition resolved - cancel alarm and reset timer
            cancelAlarm(ALARM_REQUEST_LOW)
            if (lastLowAlertTime != 0L) {
                prefs.edit().putLong(PREF_LAST_LOW_ALERT, 0L).apply()
            }
        }

        // FULL ALARM
        if (fullEnabled && currentBatteryLevel >= fullThreshold && isCharging) {
            if (!dndActive && now - lastFullAlertTime >= ALERT_REPEAT_INTERVAL) {
                prefs.edit().putLong(PREF_LAST_FULL_ALERT, now).apply()
                speakAlert("CẢNH BÁO MỨC PIN")
                scheduleNextAlarm(ALARM_REQUEST_FULL, ALERT_REPEAT_INTERVAL)
                Log.d(TAG, "Full battery alert at $currentBatteryLevel%")
            }
        } else {
            cancelAlarm(ALARM_REQUEST_FULL)
            if (lastFullAlertTime != 0L) {
                prefs.edit().putLong(PREF_LAST_FULL_ALERT, 0L).apply()
            }
        }
    }

    /**
     * Kiểm tra giờ hiện tại có nằm trong khoảng Không làm phiền không.
     * Hỗ trợ khoảng vượt qua nửa đêm, ví dụ 22h → 6h.
     */
    private fun isInDndPeriod(): Boolean {
        if (!prefs.getBoolean("dnd_enabled", false)) return false
        val startHour = prefs.getInt("dnd_start_hour", 22)
        val endHour = prefs.getInt("dnd_end_hour", 6)
        val cal = java.util.Calendar.getInstance()
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            // Cùng ngày, ví dụ 9h → 17h
            currentHour in startHour until endHour
        } else {
            // Vượt nửa đêm, ví dụ 22h → 6h
            currentHour >= startHour || currentHour < endHour
        }
    }

    // AlarmManager - setExactAndAllowWhileIdle fires even in Doze mode

    private fun scheduleNextAlarm(requestCode: Int, delayMs: Long) {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_ALARM_TICK).apply { setPackage(packageName) }
        val pi = PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
        Log.d(TAG, "Alarm scheduled in ${delayMs / 60000}min (code=$requestCode)")
    }

    private fun cancelAlarm(requestCode: Int) {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_ALARM_TICK).apply { setPackage(packageName) }
        val pi = PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            Log.d(TAG, "Alarm cancelled (code=$requestCode)")
        }
    }

    private fun cancelAlarms() {
        cancelAlarm(ALARM_REQUEST_LOW)
        cancelAlarm(ALARM_REQUEST_FULL)
    }

    // TTS

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val viLocale = Locale("vi", "VN")
                val result = tts!!.setLanguage(viLocale)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!isTtsReady) {
                    tts!!.setLanguage(Locale.getDefault())
                    isTtsReady = true
                }

                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts!!.setAudioAttributes(audioAttrs)

                Log.d(TAG, "TTS initialized. Vietnamese: $isTtsReady")
            } else {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    private fun speakAlert(message: String) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS not ready")
            return
        }

        tts!!.stop()

        // Extra WakeLock for TTS duration (max 60s)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ttsWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BatteryAlarm::TtsWakeLock"
        )
        ttsWakeLock.acquire(60_000L)

        for (i in 1..TTS_REPEAT_COUNT) {
            val utteranceId = "battery_alert_$i"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val queueMode = if (i == 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts!!.speak(message, queueMode, params, utteranceId)
        }

        // Release TTS WakeLock after last utterance
        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "battery_alert_$TTS_REPEAT_COUNT") {
                    if (ttsWakeLock.isHeld) ttsWakeLock.release()
                    Log.d(TAG, "TTS complete, WakeLock released")
                }
            }
            override fun onError(utteranceId: String?) {
                if (ttsWakeLock.isHeld) ttsWakeLock.release()
            }
        })

        Log.d(TAG, "TTS speaking x$TTS_REPEAT_COUNT")
    }

    // Notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Battery Alarm Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh theo dõi pin nền"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Alarm")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_battery)
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
