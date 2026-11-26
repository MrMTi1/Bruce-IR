package com.example.bruceir

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class BruceWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var webUiHost = ""
    private var webUiOrigin = ""
    private var username = ""
    private var password = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.BRUCE)) return
        setContentView(R.layout.activity_bruce_remote)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val intentUrl = intent.getStringExtra("EXTRA_URL") ?: intent.dataString
        val configuredUrl = if (!intentUrl.isNullOrBlank()) {
            intentUrl.trim()
        } else {
            prefs.getString("bruce_url", "http://bruce.local")
                ?.trim()
                .orEmpty()
        }
        username = prefs.getString("bruce_user", "admin").orEmpty()
        password = prefs.getString("bruce_pass", "bruce").orEmpty()

        val baseUrl = when {
            configuredUrl.isBlank() -> "http://bruce.local/"
            configuredUrl.startsWith("http://", ignoreCase = true) ||
                configuredUrl.startsWith("https://", ignoreCase = true) ->
                configuredUrl
            else -> "http://${configuredUrl}"
        }
        val webUiUri = Uri.parse(baseUrl)
        val scheme = webUiUri.scheme.orEmpty().lowercase()
        webUiHost = webUiUri.host.orEmpty().lowercase()
        val authorityHost = if (webUiHost.contains(':')) "[$webUiHost]" else webUiHost
        val port = webUiUri.port
        val originPort = if (port == -1 || (scheme == "http" && port == 80) ||
            (scheme == "https" && port == 443)
        ) "" else ":$port"
        webUiOrigin = "$scheme://$authorityHost$originPort"

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.webProgress)
        status = findViewById(R.id.tvWebStatus)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String
            ) {
                if (host.equals(webUiHost, ignoreCase = true) &&
                    username.isNotEmpty() && password.isNotEmpty()
                ) {
                    handler.proceed(username, password)
                } else {
                    handler.cancel()
                    if (host.equals(webUiHost, ignoreCase = true)) {
                        showStatus(getString(R.string.bruce_web_login_required))
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                status.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                val loginScript = """
                    (function() {
                        const expectedOrigin = ${JSONObject.quote(webUiOrigin)};
                        if (location.origin !== expectedOrigin) return "wrong-origin";
                        const form = Array.from(document.forms).find(candidate => {
                            const action = new URL(candidate.action || location.href, location.href);
                            return action.origin === expectedOrigin && action.pathname === "/login";
                        });
                        if (!form) return "no-login-form";
                        if (new URLSearchParams(location.search).has("failed")) return "login-failed";
                        if (!${JSONObject.quote(username)} || !${JSONObject.quote(password)}) return "missing-credentials";
                        const usernameInput = form.elements.namedItem("username");
                        const passwordInput = form.elements.namedItem("password");
                        if (!usernameInput || !passwordInput) return "unsupported-login-form";
                        usernameInput.value = ${JSONObject.quote(username)};
                        passwordInput.value = ${JSONObject.quote(password)};
                        form.submit();
                        return "submitted";
                    })();
                """.trimIndent()
                view.evaluateJavascript(loginScript) { result ->
                    when (result) {
                        "\"login-failed\"" -> showStatus(getString(R.string.bruce_web_login_failed))
                        "\"missing-credentials\"" -> showStatus(getString(R.string.bruce_web_login_required))
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    showStatus(getString(R.string.bruce_web_load_error, error.description))
                }
            }
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        webView.loadUrl(baseUrl)
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
