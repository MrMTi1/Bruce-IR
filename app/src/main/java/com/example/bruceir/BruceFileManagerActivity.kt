package com.example.bruceir

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class BruceFileManagerActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var location: TextView
    private lateinit var storageButton: Button
    private lateinit var list: ListView
    private var storage = "LittleFS"
    private var folder = "/"
    private var entries: List<Entry> = emptyList()
    private var requestJob: Job? = null

    private val uploadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }
    private var pendingDownloadName = "download.bin"
    private val downloadPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) download(uri, pendingDownloadName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.BRUCE)) return
        storage = intent.getStringExtra("storage") ?: "LittleFS"
        buildUi()
        refresh()
    }

    override fun onDestroy() {
        requestJob?.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF070A0F.toInt())
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply { text = "‹"; setOnClickListener { finish() } })
        top.addView(TextView(this).apply {
            text = "Bruce file transfer"
            textSize = 20f
            setTextColor(-1)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(top)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        storageButton = Button(this).apply { setOnClickListener { toggleStorage() } }
        actions.addView(storageButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "UP"
            setOnClickListener {
                if (folder != "/") {
                    folder = folder.substringBeforeLast('/').ifEmpty { "/" }
                    updateHeader()
                    refresh()
                }
            }
        })
        actions.addView(Button(this).apply {
            text = "UPLOAD"
            setOnClickListener { uploadPicker.launch(arrayOf("*/*")) }
        })
        actions.addView(Button(this).apply { text = "REFRESH"; setOnClickListener { refresh() } })
        root.addView(actions)
        location = TextView(this).apply { setTextColor(0xFF43C59E.toInt()); textSize = 13f }
        root.addView(location)
        status = TextView(this).apply { setTextColor(0xFF9EADBD.toInt()); textSize = 13f }
        root.addView(status)
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            if (entry.directory) {
                folder = childPath(folder, entry.name)
                refresh()
            } else showFileActions(entry)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val entry = entries[position]
            if (!entry.directory) showFileActions(entry)
            true
        }
        setContentView(root)
        updateHeader()
    }

    private fun toggleStorage() {
        storage = if (storage == "LittleFS") "SD" else "LittleFS"
        folder = "/"
        updateHeader()
        refresh()
    }

    private fun updateHeader() {
        storageButton.text = "STORAGE: $storage"
        location.text = "$storage:$folder"
    }

    private fun refresh() {
        requestJob?.cancel()
        status.text = "Loading Bruce files…"
        requestJob = lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { request("/listfiles?fs=${enc(storage)}&folder=${enc(folder)}").let { conn -> try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() } } } }
                .onSuccess { response ->
                    entries = parseListing(response)
                    list.adapter = ArrayAdapter(this@BruceFileManagerActivity, android.R.layout.simple_list_item_1,
                        entries.map { if (it.directory) "📁  ${it.name}" else "${it.name}   ${it.size}" })
                    status.text = if (entries.isEmpty()) "This folder is empty." else "${entries.size} item(s) — tap a file to download."
                }
                .onFailure { status.text = "Could not read Bruce storage: ${it.message ?: "connection error"}" }
        }
    }

    private fun parseListing(payload: String): List<Entry> = payload.lineSequence().mapNotNull { line ->
        val parts = line.split(':', limit = 3)
        when (parts.firstOrNull()) {
            "Fo" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let { Entry(it, true, "") }
            "Fi" -> if (parts.size == 3 && parts[1].isNotBlank()) Entry(parts[1], false, parts[2]) else null
            else -> null
        }
    }.toList()

    private fun showFileActions(entry: Entry) {
        val options = mutableListOf("Download to phone", "Open / edit as text")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, selected ->
                if (selected == 0) {
                    pendingDownloadName = entry.name
                    downloadPicker.launch(entry.name)
                } else showText(entry)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showText(entry: Entry) {
        requestJob?.cancel()
        status.text = "Reading ${entry.name}…"
        requestJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    request("/file?fs=${enc(storage)}&name=${enc(childPath(folder, entry.name))}&action=edit")
                        .let { conn -> try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() } }
                }
            }.onSuccess { content ->
                val editor = EditText(this@BruceFileManagerActivity).apply {
                    setText(content)
                    textSize = 13f
                    setTextColor(-1)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    gravity = Gravity.TOP or Gravity.START
                    minLines = 8
                    maxLines = 24
                    setSelection(text.length)
                }
                val dialog = AlertDialog.Builder(this@BruceFileManagerActivity)
                    .setTitle(entry.name)
                    .setView(editor)
                    .setPositiveButton("SAVE TO BRUCE", null)
                    .setNegativeButton("CLOSE", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val updatedText = editor.text.toString()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        status.text = "Saving ${entry.name} to Bruce…"
                        requestJob = lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    uploadBytes(entry.name, updatedText.toByteArray(Charsets.UTF_8))
                                }
                            }.onSuccess {
                                status.text = "Saved ${entry.name} to $storage:$folder"
                                dialog.dismiss()
                                refresh()
                            }.onFailure {
                                status.text = "Save failed: ${it.message ?: "connection error"}"
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }
                    }
                }
                dialog.show()
                status.text = "Ready."
            }.onFailure { status.text = "Could not read file: ${it.message}" }
        }
    }

    private fun upload(uri: Uri) {
        val name = queryName(uri)
        requestJob?.cancel()
        status.text = "Uploading $name…"
        requestJob = lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { uploadFile(uri, name) } }
                .onSuccess { status.text = it; refresh() }
                .onFailure { status.text = "Upload failed: ${it.message ?: "connection error"}" }
        }
    }

    private fun uploadFile(uri: Uri, name: String): String {
        // Older Bruce builds choose the upload target from the most recently
        // browsed folder. Keep them compatible while newer builds also consume
        // these values directly from the multipart request.
        request("/listfiles?fs=${enc(storage)}&folder=${enc(folder)}").let { conn -> conn.disconnect() }
        val expectedSize = querySize(uri)
        if (expectedSize >= 0L) {
            val input = contentResolver.openInputStream(uri) ?: error("The selected file could not be opened")
            return input.use { uploadStream(name, it, expectedSize) }
        }

        // Some document providers do not report a size. Spool those files to
        // the app cache so the ESPAsyncWebServer still receives a fixed-length
        // multipart request instead of Transfer-Encoding: chunked.
        val temporary = File.createTempFile("bruce-upload-", ".tmp", cacheDir)
        try {
            val input = contentResolver.openInputStream(uri) ?: error("The selected file could not be opened")
            input.use { source -> temporary.outputStream().use { source.copyTo(it, 16 * 1024) } }
            return temporary.inputStream().use { uploadStream(name, it, temporary.length()) }
        } finally {
            temporary.delete()
        }
    }

    private fun uploadBytes(name: String, bytes: ByteArray): String =
        bytes.inputStream().use { uploadStream(name, it, bytes.size.toLong()) }

    private fun uploadStream(name: String, input: java.io.InputStream, fileLength: Long): String {
        val boundary = "BruceIR-${System.currentTimeMillis()}"
        val fields = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"folder\"\r\n\r\n$folder\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"fs\"\r\n\r\n$storage\r\n")
        }.toByteArray(Charsets.UTF_8)
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .replace('"', '_').replace('\r', '_').replace('\n', '_')
        val fileHeader = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n"
            .toByteArray(Charsets.UTF_8)
        val fileTrailer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = fields.size.toLong() + fileHeader.size + fileLength + fileTrailer.size
        val conn = configuredConnection("/upload", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Connection", "close")
            setFixedLengthStreamingMode(contentLength)
        }
        try {
            conn.outputStream.use { out ->
                out.write(fields)
                out.write(fileHeader)
                val copied = input.copyTo(out, 16 * 1024)
                if (copied != fileLength) error("File size changed while reading ($copied of $fileLength bytes)")
                out.write(fileTrailer)
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error("Bruce returned HTTP $code: $body")
            return body.ifBlank { "Uploaded $name to $storage:$folder" }
        } finally { conn.disconnect() }
    }

    private fun download(uri: Uri, name: String) {
        requestJob?.cancel()
        status.text = "Downloading $name…"
        requestJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val conn = configuredConnection("/file?fs=${enc(storage)}&name=${enc(childPath(folder, name))}&action=download")
                    try {
                        conn.setRequestProperty("Accept-Encoding", "identity")
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            val message = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            error("Bruce returned HTTP $code${if (message.isBlank()) "" else ": $message"}")
                        }
                        val expectedSize = conn.contentLengthLong
                        val copied = contentResolver.openOutputStream(uri, "w")?.use { target ->
                            conn.inputStream.use { it.copyTo(target, 16 * 1024) }
                        }
                            ?: error("The destination file could not be opened")
                        if (expectedSize >= 0 && copied != expectedSize) {
                            error("Incomplete download ($copied of $expectedSize bytes)")
                        }
                    } finally { conn.disconnect() }
                }
            }.onSuccess { status.text = "Downloaded $name to this phone." }
                .onFailure { status.text = "Download failed: ${it.message ?: "connection error"}" }
        }
    }

    private fun request(path: String): HttpURLConnection = configuredConnection(path).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 30000
        val code = responseCode
        if (code !in 200..299) {
            val message = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            disconnect()
            error("Bruce returned HTTP $code${if (message.isBlank()) "" else ": $message"}")
        }
    }

    private fun configuredConnection(path: String, method: String = "GET"): HttpURLConnection {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val raw = prefs.getString("bruce_url", "http://bruce.local").orEmpty().trim()
        val base = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw.trimEnd('/')
            else -> "http://${raw.trimEnd('/')}"
        }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        val user = prefs.getString("bruce_user", "admin").orEmpty()
        val pass = prefs.getString("bruce_pass", "bruce").orEmpty()
        conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))
        return conn
    }

    private fun queryName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank) ?: "upload.bin"

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun childPath(parent: String, child: String): String = if (parent == "/") "/$child" else "$parent/$child"
    private fun enc(value: String): String = Uri.encode(value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Entry(val name: String, val directory: Boolean, val size: String)
}
