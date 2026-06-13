package com.example.bruceir

import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NavigatorActivity : AppCompatActivity() {

    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigator)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        user = prefs.getString("bruce_user", "admin") ?: "admin"
        pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        setupWebView()
        setupButtons()
    }

    private fun setupWebView() {
        webView = findViewById(R.id.wvScreen)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        // Ładujemy stronę która wyświetla ekran z Bruce'a (replika mechanizmu z WebUI)
        val html = "<html><body style='margin:0;background:black;display:flex;justify-content:center;align-items:center;'>" +
                   "<img id='screen' src='$baseUrl/getscreen' style='width:100%;image-rendering:pixelated;'/>" +
                   "<script>setInterval(() => { document.getElementById('screen').src = '$baseUrl/getscreen?t=' + Date.now(); }, 1000);</script>" +
                   "</body></html>"
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private fun setupButtons() {
        val mapping = mapOf(
            R.id.btnNavUp to "up",
            R.id.btnNavDown to "down",
            R.id.btnNavPrev to "prev",
            R.id.btnNavNext to "next",
            R.id.btnNavSel to "sel",
            R.id.btnNavEsc to "esc",
            R.id.btnNavMenu to "sel 500", // Long press per index.js
            R.id.btnNavPageUp to "nextpage",
            R.id.btnNavPageDown to "prevpage"
        )

        mapping.forEach { (id, move) ->
            findViewById<android.view.View>(id).setOnClickListener {
                vibrate()
                sendMove(move)
            }
        }

        findViewById<Button>(R.id.btnReloadScreen).setOnClickListener { webView.reload() }
    }

    private fun sendMove(direction: String) {
        Thread {
            try {
                // Nowa logika zgodna z Twoim index.js: POST /cm z parametrem cmnd=nav direction
                val url = "$baseUrl/cm"
                val response = BruceUtils.downloadFileContent("$url?cmnd=nav+$direction", user, pass)
                // Po ruchu odświeżamy obraz
                runOnUiThread { webView.reload() }
            } catch (e: Exception) {}
        }.start()
    }

    private fun vibrate() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
