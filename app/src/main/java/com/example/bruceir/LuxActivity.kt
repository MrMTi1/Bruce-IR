package com.example.bruceir

import android.graphics.Color
import android.hardware.*
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class LuxActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var tvLuxValue: TextView
    private lateinit var tvLuxDescription: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lux)

        try {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.WHITE
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        } catch (_: Exception) {}

        tvLuxValue = findViewById(R.id.tvLuxValue)
        tvLuxDescription = findViewById(R.id.tvLuxDescription)
        findViewById<ImageButton>(R.id.btnLuxBack).setOnClickListener { finish() }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            tvLuxValue.text = lux.toInt().toString()
            tvLuxDescription.text = when {
                lux < 10 -> getString(R.string.lux_dark)
                lux < 400 -> getString(R.string.lux_indoor)
                lux < 1000 -> getString(R.string.lux_overcast)
                else -> getString(R.string.lux_sunlight)
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}