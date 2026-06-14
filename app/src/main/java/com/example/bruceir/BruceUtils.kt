package com.example.bruceir

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        var name = ""; var freq = 38000
        var pattern: IntArray? = null
        var protocol: String? = null

        fun addCurrent() {
            if (name.isNotEmpty()) {
                val finalName = if (protocol != null && pattern == null) "$name ($protocol)" else name
                val finalPattern = pattern ?: intArrayOf(100, 100) // Dummy for parsed
                list.add(Command(finalName, freq, finalPattern))
            }
            name = ""; freq = 38000; pattern = null; protocol = null
        }

        content.lines().forEach { line ->
            val t = line.trim()
            if (t.startsWith("#")) {
                addCurrent()
                return@forEach
            }
            if (t.startsWith("name:")) name = t.substringAfter(":").trim()
            if (t.startsWith("frequency:")) freq = t.substringAfter(":").trim().toIntOrNull() ?: 38000
            if (t.startsWith("protocol:")) protocol = t.substringAfter(":").trim()
            if (t.startsWith("data:")) {
                val raw = t.substringAfter(":").trim().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray()
                pattern = if (raw.getOrNull(0) == 0) raw.drop(1).toIntArray() else raw
            }
        }
        addCurrent() // Add last one
        return list
    }

    fun saveAllData(context: Context, allData: IrFolder, onDone: (() -> Unit)? = null) {
        // Wykonujemy zapis w tle, aby nie blokować UI
        Thread {
            try {
                val file = java.io.File(context.filesDir, "bruce_db.json")
                val tempFile = java.io.File(context.filesDir, "bruce_db.json.tmp")
                val backupFile = java.io.File(context.filesDir, "bruce_db.json.bak")

                // Zapis do pliku tymczasowego
                tempFile.writer().use { writer ->
                    gson.toJson(allData, writer)
                }

                // Tworzenie kopii zapasowej poprzedniej wersji
                if (file.exists()) {
                    file.copyTo(backupFile, overwrite = true)
                }

                // Podmiana na nową wersję (atomowa na poziomie plików)
                if (tempFile.renameTo(file)) {
                    Log.d("BruceIR", "Zapisano bazę pomyślnie")
                } else {
                    // Jeśli rename zawiedzie, spróbujmy kopiowania
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }
                onDone?.invoke()
            } catch (e: Exception) {
                Log.e("BruceIR", "Save error: ${e.message}")
            }
        }.start()
    }

    fun loadAllData(context: Context): IrFolder {
        val file = java.io.File(context.filesDir, "bruce_db.json")
        val backupFile = java.io.File(context.filesDir, "bruce_db.json.bak")
        
        // 1. Spróbuj załadować główny plik
        if (file.exists()) {
            try {
                return streamParseJson(file.reader())
            } catch (e: Exception) {
                Log.e("BruceIR", "Błąd ładowania głównej bazy, próbuję kopię zapasową: ${e.message}")
            }
        }

        // 2. Jeśli główny zawiedzie, spróbuj kopię zapasową
        if (backupFile.exists()) {
            try {
                val root = streamParseJson(backupFile.reader())
                // Przywróć kopię do głównego pliku
                backupFile.copyTo(file, overwrite = true)
                return root
            } catch (e: Exception) {
                Log.e("BruceIR", "Błąd ładowania kopii zapasowej: ${e.message}")
            }
        }

        // 3. Ostateczność: stare SharedPreferences (z poprzednich wersji)
        val prefs = context.getSharedPreferences("ir_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("full_data_final", null)
        if (json != null) {
            try {
                val root = streamParseJson(json.reader())
                saveAllData(context, root)
                return root
            } catch (e: Exception) {}
        }

        return IrFolder("ROOT")
    }

    fun streamParseJson(reader: java.io.Reader): IrFolder {
        val jsonReader = com.google.gson.stream.JsonReader(reader)
        return readFolder(jsonReader)
    }

    private fun readFolder(reader: com.google.gson.stream.JsonReader): IrFolder {
        var name = "UNKNOWN"
        val items = mutableListOf<Any>()
        
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "items" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        items.add(readItem(reader))
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return IrFolder(name, items)
    }

    private fun readItem(reader: com.google.gson.stream.JsonReader): Any {
        var type = ""
        var name = ""
        var freq = 38000
        var pattern = intArrayOf()
        var icon: String? = null
        val folderItems = mutableListOf<Any>()

        // Musimy czytać obiekt dwa razy lub zapamiętywać pola, bo 'type' może być na końcu
        // Ale BruceIR JSON ma 'type' zwykle na początku lub w stałym miejscu.
        // Dla pewności przeczytamy jako JsonObject i skonwertujemy - to nadal lepsze niż Map<String, Any>
        val obj = com.google.gson.JsonParser.parseReader(reader).asJsonObject
        type = obj.get("type")?.asString ?: "cmd"
        name = obj.get("name")?.asString ?: ""
        
        if (type == "folder") {
            val itemsArr = obj.getAsJsonArray("items")
            itemsArr?.forEach { folderItems.add(readItemFromJson(it.asJsonObject)) }
            return IrFolder(name, folderItems)
        } else {
            freq = obj.get("frequency")?.asInt ?: 38000
            icon = obj.get("iconName")?.asString
            val pArr = obj.getAsJsonArray("pattern")
            pattern = IntArray(pArr?.size() ?: 0) { pArr.get(it).asInt }
            return Command(name, freq, pattern, icon)
        }
    }

    private fun readItemFromJson(obj: com.google.gson.JsonObject): Any {
        val type = obj.get("type")?.asString ?: "cmd"
        val name = obj.get("name")?.asString ?: ""
        
        if (type == "folder") {
            val items = mutableListOf<Any>()
            obj.getAsJsonArray("items")?.forEach { items.add(readItemFromJson(it.asJsonObject)) }
            return IrFolder(name, items)
        } else {
            val freq = obj.get("frequency")?.asInt ?: 38000
            val icon = obj.get("iconName")?.asString
            val pArr = obj.getAsJsonArray("pattern")
            val pattern = IntArray(pArr?.size() ?: 0) { pArr.get(it).asInt }
            return Command(name, freq, pattern, icon)
        }
    }

}
