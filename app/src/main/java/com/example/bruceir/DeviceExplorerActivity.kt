package com.example.bruceir

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URLEncoder

class DeviceExplorerActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: DeviceAdapter
    private var currentPath = "/"
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private val gson = Gson()

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
            } else if (item.name.lowercase().endsWith(".ir")) {
                downloadAndImport(item.fullPath)
            }
        }
        rv.adapter = adapter

        loadPath("/")
    }

    private fun loadPath(path: String) {
        currentPath = path
        Thread {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            // Próbujemy różnych punktów końcowych dla listy plików
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
                runOnUiThread { Toast.makeText(this, "Nie udało się wczytać folderu", Toast.LENGTH_SHORT).show() }
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

    private fun downloadAndImport(path: String) {
        Thread {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val url = "$baseUrl/download?drive=SD&path=$encodedPath"
            val content = BruceUtils.downloadFileContent(url, user, pass)
            
            if (content != null && content.contains("name:")) {
                val commands = BruceUtils.parseIrContent(content)
                if (commands.isNotEmpty()) {
                    runOnUiThread {
                        saveToDownloaded(path.substringAfterLast("/").uppercase().replace(".IR", ""), commands)
                        Toast.makeText(this, "Zaimportowano!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun saveToDownloaded(folderName: String, commands: List<Command>) {
        val allData = BruceUtils.loadAllData(this)
        val downloadedFolder = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder ?: IrFolder("DOWNLOADED").also { allData.items.add(it) }
        downloadedFolder.items.add(IrFolder(folderName, commands.toMutableList() as MutableList<Any>))
        BruceUtils.saveAllData(this, allData)
    }
}
