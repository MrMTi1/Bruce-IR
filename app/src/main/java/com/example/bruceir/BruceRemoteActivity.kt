package com.example.bruceir

import android.os.Bundle
import android.webkit.*
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class BruceRemoteActivity : AppCompatActivity() {

    private lateinit var webView: WebView

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
        setContentView(R.layout.activity_bruce_remote)

        val initialUrl = intent.getStringExtra("url") ?: "http://bruce.local"
        val user = intent.getStringExtra("user") ?: "admin"
        val pass = intent.getStringExtra("pass") ?: "bruce"

        // Wyciągamy bazowy adres (host), aby móc budować poprawne linki pobierania
        val baseUrl = try {
            val uri = java.net.URI(initialUrl)
            "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        } catch (e: Exception) {
            initialUrl.removeSuffix("/")
        }

        webView = findViewById(R.id.webView)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                handler?.proceed(user, pass)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val requestUrl = request?.url?.toString() ?: ""
                android.util.Log.d("BruceIR", "Próba załadowania URL: $requestUrl")
                if (requestUrl.contains("/download")) {
                    // Naprawiamy URL jeśli jest relatywny
                    val finalDownloadUrl = if (requestUrl.startsWith("/")) "$baseUrl$requestUrl" else requestUrl
                    handleManualDownload(finalDownloadUrl, user, pass)
                    return true 
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.loadUrl("javascript:(function() { " +
                    "document.addEventListener('click', function(e) {" +
                    "  var target = e.target.closest('a');" +
                    "  if (target && target.href.indexOf('/download') !== -1) {" +
                    "    e.preventDefault();" +
                    "    window.location.href = target.href;" +
                    "  }" +
                    "}, true);" +
                    "})()")
            }
        }
        
        webView.setDownloadListener { downloadUrl, _, _, _, _ ->
            val finalDownloadUrl = if (downloadUrl.startsWith("/")) "$baseUrl$downloadUrl" else downloadUrl
            handleManualDownload(finalDownloadUrl, user, pass)
        }
        
        val auth = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        val headers = mapOf("Authorization" to "Basic $auth")
        webView.loadUrl(initialUrl, headers)
    }

    private fun handleManualDownload(url: String, user: String, pass: String) {
        // Ulepszona próba wyciągnięcia nazwy pliku z parametrów URL (np. name=... lub file=...)
        val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
        val fileName = when {
            decodedUrl.contains("name=") -> decodedUrl.substringAfter("name=").substringAfterLast("/").substringBefore("&")
            decodedUrl.contains("file=") -> decodedUrl.substringAfter("file=").substringAfterLast("/").substringBefore("&")
            else -> decodedUrl.substringBefore("?").substringAfterLast("/")
        }.uppercase().replace(".IR", "")

        val finalFileName = if (fileName.isBlank() || fileName.contains("DOWNLOAD")) "IMPORT_BRUCE" else fileName

        Thread {
            val content = BruceUtils.downloadFileContent(url, user, pass)
            android.util.Log.d("BruceIR", "Pobrano treść pliku ($finalFileName): $content")
            
            if (content != null && content.contains("name:")) {
                val commands = BruceUtils.parseIrContent(content)
                if (commands.isNotEmpty()) {
                    runOnUiThread {
                        importToDownloaded(finalFileName, commands)
                        android.widget.Toast.makeText(this, getString(R.string.toast_imported, "DOWNLOADED", finalFileName), android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Error: No commands found in $finalFileName", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Error: Invalid .ir file format", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun importToDownloaded(folderName: String, commands: List<Command>) {
        val allData = BruceUtils.loadAllData(this)
        var downloadedFolder = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder
        if (downloadedFolder == null) {
            downloadedFolder = IrFolder("DOWNLOADED")
            allData.items.add(downloadedFolder)
        }
        downloadedFolder.items.add(IrFolder(folderName, commands.toMutableList() as MutableList<Any>))
        BruceUtils.saveAllData(this, allData)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}