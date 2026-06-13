package com.example.bruceir

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class RfAnalyzerActivity : AppCompatActivity() {

    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private var isRecording = false

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "en") ?: "en"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rf_analyzer)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        user = prefs.getString("bruce_user", "admin") ?: "admin"
        pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        val etFreq = findViewById<EditText>(R.id.etRfFreq)
        val tvInfo = findViewById<TextView>(R.id.tvRfInfo)

        findViewById<Button>(R.id.btnSetFreq).setOnClickListener {
            val freq = etFreq.text.toString()
            Thread {
                // Endpoint Bruce: /rf/config?freq=433.92
                val url = "$baseUrl/rf/config?freq=$freq"
                val response = BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread { 
                    Toast.makeText(this, "Bruce tuned to $freq MHz", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRfRecord).setOnClickListener {
            isRecording = !isRecording
            val btn = it as Button
            if (isRecording) {
                btn.text = "STOP CAPTURE"
                tvInfo.text = "Capturing RAW pulses via Bruce..."
                // Komenda do Bruce'a: zacznij streamować RAW
                sendCaptureCommand(true)
            } else {
                btn.text = "START CAPTURE RAW"
                sendCaptureCommand(false)
            }
        }
    }

    private fun sendCaptureCommand(start: Boolean) {
        Thread {
            val url = "$baseUrl/rf/capture?action=${if (start) "start" else "stop"}"
            BruceUtils.downloadFileContent(url, user, pass)
        }.start()
    }
}
