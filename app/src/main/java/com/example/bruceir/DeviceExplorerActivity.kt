package com.example.bruceir

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URLEncoder

class DeviceExplorerActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: DeviceAdapter
    private var currentPath = "/"
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private val gson = Gson()
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
        
        baseUrl = intent.getStringExtra("url") ?: ""
        user = intent.getStringExtra("user") ?: ""
        pass = intent.getStringExtra("pass") ?: ""

        setContentView(R.layout.activity_device_explorer)

        findViewById<TextView>(R.id.tvExplorerTitle).text = "Bruce Explorer"
        
        rv = findViewById(R.id.rvExplorer)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(emptyList()) { item ->
            if (item.isDir) {
                loadPath(item.fullPath)
            } else {
                handleFileAction(item)
            }
        }
        rv.adapter = adapter

        loadPath("/")
    }

    private fun loadPath(path: String) {
        currentPath = path
        Thread {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val endpoints = listOf(
                "$baseUrl/list?drive=SD&path=$encodedPath",
                "$baseUrl/list?dir=$encodedPath",
                "$baseUrl/api/files?path=$encodedPath"
            )

            var success = false
            for (url in endpoints) {
                val response = BruceUtils.downloadFileContent(url, user, pass)
                if (response != null) {
                    val files = parseResponse(response, path)
                    if (files.isNotEmpty()) {
                        runOnUiThread { adapter.updateList(files) }
                        success = true
                        break
                    }
                }
            }

            if (!success) {
                runOnUiThread { Toast.makeText(this, "Load failed", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun parseResponse(response: String, parentPath: String): List<DeviceAdapter.BruceFile> {
        val result = mutableListOf<DeviceAdapter.BruceFile>()
        try {
            if (response.trim().startsWith("[")) {
                val list = gson.fromJson<List<Map<String, Any>>>(response, object : TypeToken<List<Map<String, Any>>>() {}.type)
                list.forEach { item ->
                    val name = (item["name"] ?: item["filename"] ?: item["text"]) as? String ?: ""
                    if (name == "." || name == "..") return@forEach
                    val type = (item["type"] as? String ?: "").lowercase()
                    val isDir = (item["isDir"] as? Boolean) ?: (type == "directory" || type == "dir")
                    val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"
                    result.add(DeviceAdapter.BruceFile(name, isDir, fullPath))
                }
            }
        } catch (e: Exception) {}
        return result
    }

    private fun handleFileAction(item: DeviceAdapter.BruceFile) {
        val ext = item.name.substringAfterLast(".").lowercase()
        Thread {
            val encodedPath = URLEncoder.encode(item.fullPath, "UTF-8")
            val url = "$baseUrl/download?drive=SD&path=$encodedPath"
            val data = BruceUtils.downloadBinaryFile(url, user, pass)
            
            if (data == null) {
                runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            if (ext == "ir") {
                val content = String(data)
                val commands = BruceUtils.parseIrContent(content)
                if (commands.isNotEmpty()) {
                    runOnUiThread {
                        saveToDownloaded(item.name.uppercase().replace(".IR", ""), commands)
                        Toast.makeText(this, "Imported!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val file = File(getExternalFilesDir(null), item.name)
                file.writeBytes(data)
                runOnUiThread {
                    if (ext == "wav") {
                        showPlayDialog(file)
                    } else {
                        Toast.makeText(this, "Saved: ${item.name}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun showPlayDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Play Audio")
            .setMessage("File: ${file.name}")
            .setPositiveButton("Play") { _, _ ->
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer.create(this, Uri.fromFile(file))
                    mediaPlayer?.start()
                } catch (e: Exception) {
                    Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Stop") { _, _ -> mediaPlayer?.stop() }
            .show()
    }

    private fun saveToDownloaded(folderName: String, commands: List<Command>) {
        val allData = BruceUtils.loadAllData(this)
        val downloadedFolder = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder ?: IrFolder("DOWNLOADED").also { allData.items.add(it) }
        downloadedFolder.items.add(IrFolder(folderName, commands.toMutableList() as MutableList<Any>))
        BruceUtils.saveAllData(this, allData)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
