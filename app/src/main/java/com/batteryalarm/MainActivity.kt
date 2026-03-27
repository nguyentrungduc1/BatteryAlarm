package com.batteryalarm

import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.batteryalarm.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("battery_alarm_prefs", MODE_PRIVATE)

        setupUI()
        requestPermissions()
        requestIgnoreBatteryOptimization()
        startBatteryService()
    }

    private fun setupUI() {
        // Load saved thresholds
        val savedLow = prefs.getInt("low_threshold", 20)
        val savedFull = prefs.getInt("full_threshold", 90)

        binding.etLowThreshold.setText(savedLow.toString())
        binding.etFullThreshold.setText(savedFull.toString())

        // Toggle switches
        binding.switchLowAlarm.isChecked = prefs.getBoolean("low_alarm_enabled", true)
        binding.switchFullAlarm.isChecked = prefs.getBoolean("full_alarm_enabled", true)

        binding.switchLowAlarm.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("low_alarm_enabled", isChecked).apply()
            updateService()
        }

        binding.switchFullAlarm.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("full_alarm_enabled", isChecked).apply()
            updateService()
        }

        // Save button
        binding.btnSave.setOnClickListener {
            val lowStr = binding.etLowThreshold.text.toString()
            val fullStr = binding.etFullThreshold.text.toString()

            val low = lowStr.toIntOrNull()
            val full = fullStr.toIntOrNull()

            if (low == null || low < 0 || low > 100) {
                Toast.makeText(this, "Mức pin thấp phải từ 0 đến 100%", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (full == null || full < 0 || full > 100) {
                Toast.makeText(this, "Mức pin đầy phải từ 0 đến 100%", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (low >= full) {
                Toast.makeText(this, "Mức pin thấp phải nhỏ hơn mức pin đầy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putInt("low_threshold", low)
                .putInt("full_threshold", full)
                .apply()

            Toast.makeText(this, "Đã lưu: Pin thấp ${low}% | Pin đầy ${full}%", Toast.LENGTH_SHORT).show()
            updateService()
        }

        // Update battery info display
        updateBatteryDisplay()
    }

    private fun updateBatteryDisplay() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, intentFilter)
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (scale > 0) (level * 100 / scale) else 0

            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
            val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0f

            binding.tvBatteryLevel.text = "${pct}%"
            binding.tvCharging.text = if (isCharging) "⚡ Đang sạc" else "🔋 Không sạc"
            binding.tvTemp.text = "Nhiệt độ: ${temp}°C"
            binding.tvVoltage.text = "Điện áp: ${String.format("%.2f", voltage)}V"
            binding.batteryProgress.progress = pct
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Some devices may not support this
            }
        }
    }

    private fun startBatteryService() {
        val intent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateService() {
        // Restart service to pick up new settings
        val intent = Intent(this, BatteryMonitorService::class.java)
        intent.action = BatteryMonitorService.ACTION_UPDATE_SETTINGS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryDisplay()
    }
}
