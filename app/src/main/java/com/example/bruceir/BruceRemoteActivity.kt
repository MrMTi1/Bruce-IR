package com.example.bruceir

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.URI
import java.net.URLDecoder

class BruceRemoteActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null

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

        val baseUrl = try {
            val uri = URI(initialUrl)
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
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                handler?.proceed(user, pass)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                if (url.contains("/download")) {
                    val finalUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                    handleGenericDownload(finalUrl, user, pass)
                    return true 
                }
                return false
            }
        }
        
        webView.setDownloadListener { downloadUrl, _, _, _, _ ->
            val finalUrl = if (downloadUrl.startsWith("/")) "$baseUrl$downloadUrl" else downloadUrl
            handleGenericDownload(finalUrl, user, pass)
        }
        
        val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        val headers = mapOf("Authorization" to "Basic $auth")
        webView.loadUrl(initialUrl, headers)
    }

    private fun handleGenericDownload(url: String, user: String, pass: String) {
        val decodedUrl = URLDecoder.decode(url, "UTF-8")
        val fileName = when {
            decodedUrl.contains("name=") -> decodedUrl.substringAfter("name=").substringAfterLast("/").substringBefore("&")
            decodedUrl.contains("file=") -> decodedUrl.substringAfter("file=").substringAfterLast("/").substringBefore("&")
            else -> decodedUrl.substringBefore("?").substringAfterLast("/")
        }

        Thread {
            val data = BruceUtils.downloadBinaryFile(url, user, pass)
            if (data == null) {
                runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            if (fileName.lowercase().endsWith(".ir")) {
                val content = String(data)
                val commands = BruceUtils.parseIrContent(content)
                if (commands.isNotEmpty()) {
                    runOnUiThread {
                        importToDownloaded(fileName.uppercase().replace(".IR", ""), commands)
                        Toast.makeText(this, "Imported: $fileName", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Save to local file
                val file = File(getExternalFilesDir(null), fileName)
                file.writeBytes(data)
                
                runOnUiThread {
                    if (fileName.lowercase().endsWith(".wav")) {
                        showPlayDialog(file)
                    } else {
                        Toast.makeText(this, "Saved: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun showPlayDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Audio File")
            .setMessage("Play ${file.name}?")
            .setPositiveButton("Play") { _, _ ->
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer.create(this, Uri.fromFile(file))
                    mediaPlayer?.start()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error playing audio", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close") { _, _ -> mediaPlayer?.stop() }
            .show()
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

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
