package com.example.bruceir

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NavigatorActivity : AppCompatActivity() {

    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigator)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        user = prefs.getString("bruce_user", "admin") ?: "admin"
        pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        findViewById<Button>(R.id.btnNavUp).setOnClickListener { sendNav("up") }
        findViewById<Button>(R.id.btnNavDown).setOnClickListener { sendNav("down") }
        findViewById<Button>(R.id.btnNavLeft).setOnClickListener { sendNav("left") }
        findViewById<Button>(R.id.btnNavRight).setOnClickListener { sendNav("right") }
        findViewById<Button>(R.id.btnNavOk).setOnClickListener { sendNav("ok") }
        findViewById<Button>(R.id.btnNavBack).setOnClickListener { sendNav("back") }
    }

    private fun sendNav(action: String) {
        Thread {
            try {
                // Endpoint w firmware Bruce do nawigacji: /nav?move=...
                val url = "$baseUrl/nav?move=$action"
                BruceUtils.downloadFileContent(url, user, pass)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Nav Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
