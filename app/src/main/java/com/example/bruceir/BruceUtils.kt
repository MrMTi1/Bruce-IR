package com.example.bruceir

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object BruceUtils {
    private val gson = Gson()

    fun downloadFileContent(url: String, user: String, pass: String): String? {
        val data = downloadBinaryFile(url, user, pass) ?: return null
        return String(data)
    }

    fun downloadBinaryFile(url: String, user: String, pass: String): ByteArray? {
        var finalUrl = if (!url.startsWith("http")) {
            if (url.startsWith("/")) "http://bruce.local$url"
            else "http://$url"
        } else url
        
        // Zabezpieczenie: jeśli ktoś wpisał samo IP bez / na końcu i bez parametrów
        if (finalUrl.count { it == '/' } == 2) {
             // np. http://192.168.1.1 -> dodaj / na końcu
             finalUrl += "/"
        }

        Log.d("BruceIR", "Pobieranie binarne z URL: $finalUrl")
        return try {
            val connection = URL(finalUrl).openConnection() as HttpURLConnection
            
            val cookies = android.webkit.CookieManager.getInstance().getCookie(finalUrl)
            if (cookies != null) {
                connection.setRequestProperty("Cookie", cookies)
            }

            val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $auth")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            
            if (connection.responseCode == 200) {
                connection.inputStream.readBytes()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("BruceIR", "Download exception: ${e.message}")
            null
        }
    }

    fun parseIrContent(content: String): List<Command> {
        val list = mutableListOf<Command>()
        
        val trimmedContent = content.trim()
        if (trimmedContent.startsWith("{") || trimmedContent.startsWith("[")) {
            return emptyList()
        }

        var name = ""
        var freq = 38000
        var pattern: IntArray? = null
        var protocol: String? = null
        var address: String? = null
        var command: String? = null

        fun addCurrent() {
            if (name.isNotEmpty()) {
                val effectivePattern = if (pattern != null && pattern!!.isNotEmpty()) {
                    pattern!!
                } else if (!protocol.isNullOrBlank() && (!address.isNullOrBlank() || !command.isNullOrBlank())) {
                    try {
                        IrProtocolEncoder.encodeToRawPattern(protocol!!, address ?: "0x0", command ?: "0x0")
                    } catch (e: Exception) {
                        intArrayOf(100, 100)
                    }
                } else {
                    intArrayOf(100, 100)
                }
                val cmd = Command(name, if (freq > 0) freq else 38000, effectivePattern)
                cmd.protocol = protocol
                cmd.address = address
                cmd.command = command
                list.add(cmd)
            }
            name = ""
            freq = 38000
            pattern = null
            protocol = null
            address = null
            command = null
        }

        content.lines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) {
                return@forEach
            }

            val colonIdx = t.indexOf(':')
            if (colonIdx != -1) {
                val key = t.substring(0, colonIdx).trim().lowercase()
                val value = t.substring(colonIdx + 1).trim()

                when (key) {
                    "name", "button", "key", "title", "label" -> {
                        addCurrent()
                        name = value
                    }
                    "frequency", "freq" -> {
                        freq = value.toIntOrNull() ?: 38000
                    }
                    "protocol" -> {
                        protocol = value
                    }
                    "address" -> {
                        address = value
                    }
                    "command" -> {
                        command = value
                    }
                    "data", "raw_data", "raw data" -> {
                        val raw = value.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { abs(it.trim().toIntOrNull() ?: 0) }.toIntArray()
                        pattern = if (raw.isNotEmpty() && raw[0] == 0) raw.drop(1).toIntArray() else raw
                    }
                }
            } else {
                val numbers = t.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
                if (numbers.size > 3) {
                    val raw = numbers.map { abs(it) }.toIntArray()
                    pattern = if (raw.isNotEmpty() && raw[0] == 0) raw.drop(1).toIntArray() else raw
                }
            }
        }
        addCurrent()

        if (list.isEmpty() && trimmedContent.isNotEmpty()) {
            val allNumbers = trimmedContent.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
            if (allNumbers.size > 3) {
                val raw = allNumbers.map { abs(it) }.toIntArray()
                val pat = if (raw.isNotEmpty() && raw[0] == 0) raw.drop(1).toIntArray() else raw
                list.add(Command("Imported Signal", 38000, pat))
            } else {
                list.add(Command("Signal", 38000, intArrayOf(100, 100)))
            }
        }

        Log.d("BruceIR", "Parsed ${list.size} commands from IR content.")
        return list
    }

    fun saveAllData(context: Context, allData: IrFolder, onDone: (() -> Unit)? = null) {
        // Zachowujemy wsteczną kompatybilność, ale logikę przenosimy do suspend
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            saveAllDataSuspend(context, allData)
            withContext(Dispatchers.Main) {
                onDone?.invoke()
            }
        }
    }

    suspend fun saveAllDataSuspend(context: Context, allData: IrFolder) = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(context.filesDir, "bruce_db.json")
            val tempFile = java.io.File(context.filesDir, "bruce_db.json.tmp")
            val backupFile = java.io.File(context.filesDir, "bruce_db.json.bak")

            val cleanRoot = copyFolderWithoutFlipper(allData)

            tempFile.writer().use { writer ->
                gson.toJson(cleanRoot, writer)
            }

            if (file.exists()) {
                file.copyTo(backupFile, overwrite = true)
            }

            if (tempFile.renameTo(file)) {
                Log.d("BruceIR", "Zapisano bazę pomyślnie")
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e("BruceIR", "Save error: ${e.message}")
        }
    }

    private fun copyFolderWithoutFlipper(folder: IrFolder): IrFolder {
        val newItems = mutableListOf<Any>()
        folder.items.forEach { item ->
            if (item is IrFolder) {
                // Pomijamy tylko bazę zarządzaną przez aplikację (nie folder użytkownika,
                // którego nazwa przypadkiem zawiera słowo "Flipper").
                if (!BruceFlipperCache.isManagedFolderName(item.name)) {
                    newItems.add(copyFolderWithoutFlipper(item))
                }
            } else {
                newItems.add(item)
            }
        }
        return IrFolder(folder.name, newItems)
    }

    suspend fun loadAllDataSuspend(context: Context): IrFolder = withContext(Dispatchers.IO) {
        val file = java.io.File(context.filesDir, "bruce_db.json")
        val backupFile = java.io.File(context.filesDir, "bruce_db.json.bak")
        
        var loadedRoot: IrFolder? = null

        if (file.exists() && file.length() > 0L) {
            try {
                file.reader().use { reader ->
                    loadedRoot = streamParseJson(reader)
                }
            } catch (e: Exception) {
                Log.e("BruceIR", "Błąd ładowania głównej bazy: ${e.message}")
            }
        }

        if (loadedRoot == null && backupFile.exists() && backupFile.length() > 0L) {
            try {
                backupFile.reader().use { reader ->
                    loadedRoot = streamParseJson(reader)
                }
                backupFile.copyTo(file, overwrite = true)
            } catch (e: Exception) {
                Log.e("BruceIR", "Błąd ładowania kopii zapasowej: ${e.message}")
            }
        }

        if (loadedRoot == null) {
            try {
                val prefs = context.getSharedPreferences("ir_prefs", Context.MODE_PRIVATE)
                val json = prefs.getString("full_data_final", null)
                if (!json.isNullOrBlank()) {
                    json.reader().use { reader ->
                        loadedRoot = streamParseJson(reader)
                    }
                    saveAllDataSuspend(context, loadedRoot!!)
                }
            } catch (e: Exception) {
                Log.e("BruceIR", "Błąd ładowania SharedPreferences: ${e.message}")
            }
        }

        val root = loadedRoot ?: IrFolder("ROOT")
        ensureBuiltInFlipperDb(context, root)
        return@withContext root
    }

    fun loadAllData(context: Context): IrFolder {
        val file = java.io.File(context.filesDir, "bruce_db.json")
        val root = if (file.exists() && file.length() > 0L) {
            try {
                file.reader().use { reader ->
                    streamParseJson(reader)
                }
            } catch (e: Exception) {
                IrFolder("ROOT")
            }
        } else {
            IrFolder("ROOT")
        }
        ensureBuiltInFlipperDb(context, root)
        return root
    }

    fun ensureBuiltInFlipperDb(context: Context, root: IrFolder) {
        removeFlipperFoldersRecursively(root)

        val targetFolder = root.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name.contains("Kody i piloty IR", ignoreCase = true) ||
            it.name.contains("PILOTY", ignoreCase = true)
        } ?: root

        // Jeden zarządzany folder-placeholder, zawsze na początku listy pilotów.
        BruceFlipperCache.locateOrCreate(targetFolder)
    }

    private fun removeFlipperFoldersRecursively(folder: IrFolder) {
        folder.items.removeAll { it is IrFolder && BruceFlipperCache.isManagedFolderName(it.name) }
        folder.items.forEach {
            if (it is IrFolder) removeFlipperFoldersRecursively(it)
        }
    }

    fun parseJsonContent(content: String): Any? {
        return try {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                streamParseJson(trimmed.reader())
            } else if (trimmed.startsWith("[")) {
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val rawList: List<Map<String, Any>> = gson.fromJson(trimmed, type)
                val items = mutableListOf<Any>()
                rawList.forEach { map ->
                    val name = map["name"]?.toString() ?: map["title"]?.toString() ?: map["brand"]?.toString() ?: "Item"
                    val freq = (map["frequency"] as? Number)?.toInt() ?: (map["freq"] as? Number)?.toInt() ?: 38000
                    val patternRaw = map["pattern"] ?: map["data"] ?: map["timings"]
                    val pattern = when (patternRaw) {
                        is List<*> -> patternRaw.mapNotNull { (it as? Number)?.toInt() }.toIntArray()
                        else -> intArrayOf()
                    }
                    val protocol = map["protocol"]?.toString() ?: map["type"]?.toString()
                    val address = map["address"]?.toString() ?: map["addr"]?.toString()
                    val command = map["command"]?.toString() ?: map["cmd"]?.toString() ?: map["code"]?.toString()

                    val cmd = Command(name, freq, pattern)
                    cmd.protocol = protocol
                    cmd.address = address
                    cmd.command = command
                    items.add(cmd)
                }
                IrFolder("GitHub JSON Import", items)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun streamParseJson(reader: java.io.Reader): IrFolder {
        val jsonReader = com.google.gson.stream.JsonReader(reader)
        jsonReader.isLenient = true
        return readFolder(jsonReader)
    }

    private fun readFolder(reader: com.google.gson.stream.JsonReader): IrFolder {
        var name = "Nowy Folder"
        val items = mutableListOf<Any>()
        
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                val propName = reader.nextName().lowercase()
                when (propName) {
                    "name", "title", "label", "brand", "model", "remote" -> {
                        try {
                            val n = reader.nextString()
                            name = when {
                                n.isEmpty() || n == "UNKNOWN" -> "Nowy Folder"
                                n == "ROOT" -> "Moje Piloty"
                                else -> n
                            }
                        } catch (_: Exception) { reader.skipValue() }
                    }
                    "items", "remotes", "devices", "commands", "buttons", "keys", "categories", "subcategories", "children" -> {
                        try {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                try {
                                    val item = readItem(reader)
                                    if (item != null) items.add(item)
                                } catch (e: Exception) {
                                    try { reader.skipValue() } catch (_: Exception) {}
                                }
                            }
                            reader.endArray()
                        } catch (e: Exception) {
                            try { reader.skipValue() } catch (_: Exception) {}
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (e: Exception) {
            Log.e("BruceIR", "readFolder error: ${e.message}")
        }
        return IrFolder(name, items)
    }

    private fun readItem(reader: com.google.gson.stream.JsonReader): Any? {
        var name = ""
        var freq = 38000
        val patternList = mutableListOf<Int>()
        var protocol: String? = null
        var address: String? = null
        var command: String? = null
        val subItems = mutableListOf<Any>()
        var hasSubFoldersOrItems = false
        var explicitType: String? = null

        try {
            reader.beginObject()
            while (reader.hasNext()) {
                val propName = reader.nextName().lowercase()
                val peek = reader.peek()
                when (propName) {
                    "name", "title", "label", "brand", "model", "remote" -> {
                        if (peek == com.google.gson.stream.JsonToken.STRING || peek == com.google.gson.stream.JsonToken.NUMBER) {
                            name = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "frequency", "freq" -> {
                        if (peek == com.google.gson.stream.JsonToken.NUMBER || peek == com.google.gson.stream.JsonToken.STRING) {
                            freq = reader.nextString().toIntOrNull() ?: 38000
                        } else {
                            reader.skipValue()
                        }
                    }
                    "protocol" -> {
                        if (peek == com.google.gson.stream.JsonToken.STRING) {
                            protocol = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "type" -> {
                        if (peek == com.google.gson.stream.JsonToken.STRING) {
                            explicitType = reader.nextString().lowercase()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "address", "addr" -> {
                        if (peek == com.google.gson.stream.JsonToken.STRING || peek == com.google.gson.stream.JsonToken.NUMBER) {
                            address = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "command", "cmd", "code" -> {
                        if (peek == com.google.gson.stream.JsonToken.STRING || peek == com.google.gson.stream.JsonToken.NUMBER) {
                            command = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "pattern", "data", "timings", "raw" -> {
                        if (peek == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                            try {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    try { patternList.add(reader.nextInt()) } catch (_: Exception) { reader.skipValue() }
                                }
                                reader.endArray()
                            } catch (_: Exception) { reader.skipValue() }
                        } else if (peek == com.google.gson.stream.JsonToken.STRING) {
                            val s = reader.nextString()
                            s.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }.forEach { patternList.add(abs(it)) }
                        } else {
                            reader.skipValue()
                        }
                    }
                    "items", "categories", "subcategories", "children", "commands", "buttons", "keys", "remotes", "devices" -> {
                        if (peek == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                            hasSubFoldersOrItems = true
                            try {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val sub = readItem(reader)
                                    if (sub != null) subItems.add(sub)
                                }
                                reader.endArray()
                            } catch (_: Exception) { reader.skipValue() }
                        } else {
                            reader.skipValue()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (e: Exception) {
            return null
        }

        val hasSignalData = patternList.isNotEmpty() || !protocol.isNullOrBlank() || !address.isNullOrBlank() || !command.isNullOrBlank() || explicitType == "cmd" || explicitType == "command" || explicitType == "button" || explicitType == "key" || explicitType == "raw" || explicitType == "parsed"

        return if (hasSubFoldersOrItems && !hasSignalData) {
            IrFolder(name.ifEmpty { "Folder" }, subItems)
        } else {
            val effectivePattern = if (patternList.isNotEmpty()) {
                patternList.toIntArray()
            } else if (!protocol.isNullOrBlank() && (!address.isNullOrBlank() || !command.isNullOrBlank())) {
                try {
                    IrProtocolEncoder.encodeToRawPattern(protocol, address ?: "0x0", command ?: "0x0")
                } catch (e: Exception) {
                    intArrayOf(100, 100)
                }
            } else {
                intArrayOf(100, 100)
            }
            val cmd = Command(name.ifEmpty { "Komenda" }, freq, effectivePattern)
            cmd.protocol = protocol
            cmd.address = address
            cmd.command = command
            cmd
        }
    }

    fun showNotification(context: Context, title: String, message: String) {
        val channelId = "bruce_ir_wearable_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "BruceIR Wearable Alerts", android.app.NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: Exception) {}
    }

}
