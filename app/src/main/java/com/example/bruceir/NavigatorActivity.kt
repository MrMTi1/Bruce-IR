package com.example.bruceir

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.HttpAuthHandler
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
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                handler?.proceed(user, pass)
            }
        }
        webView.settings.javaScriptEnabled = true
        
        val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        // Replikacja mechanizmu odświeżania z WebUI Bruce'a z uwzględnieniem Auth
        val html = """
            <html>
            <body style='margin:0;background:black;display:flex;justify-content:center;align-items:center;height:100vh;overflow:hidden;'>
                <img id='screen' src='${cleanUrl}getscreen' style='width:100%; height:auto; image-rendering:pixelated;'/>
                <script>
                    function refresh() {
                        const img = document.getElementById('screen');
                        const timestamp = Date.now();
                        img.src = '${cleanUrl}getscreen?t=' + timestamp;
                    }
                    setInterval(refresh, 1000);
                </script>
            </body>
            </html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL(cleanUrl, html, "text/html", "UTF-8", null)
    }

    private fun setupButtons() {
        val mapping = mapOf(
            R.id.btnNavUp to "up",
            R.id.btnNavDown to "down",
            R.id.btnNavPrev to "prev",
            R.id.btnNavNext to "next",
            R.id.btnNavSel to "sel",
            R.id.btnNavEsc to "esc",
            R.id.btnNavMenu to "sel 500",
            R.id.btnNavPageUp to "nextpage",
            R.id.btnNavPageDown to "prevpage"
        )

        val cleanUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length-1) else baseUrl

        mapping.forEach { (id, move) ->
            findViewById<android.view.View>(id).setOnClickListener {
                vibrate()
                sendMove(cleanUrl, move)
            }
        }

        findViewById<Button>(R.id.btnReloadScreen).setOnClickListener { webView.reload() }
    }

    private fun sendMove(url: String, direction: String) {
        Thread {
            try {
                // Bruce Firmware oczekuje: GET /cm?cmnd=nav+up
                val fullUrl = "$url/cm?cmnd=nav+$direction"
                BruceUtils.downloadFileContent(fullUrl, user, pass)
                runOnUiThread { 
                   android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                       webView.reload() 
                   }, 200)
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun vibrate() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
