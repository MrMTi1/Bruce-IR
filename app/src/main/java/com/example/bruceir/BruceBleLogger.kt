package com.example.bruceir

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object BruceBleLogger {

    fun saveBleLogToDocuments(context: Context, deviceName: String, macAddress: String, logContent: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ble_log_${deviceName.filter { it.isLetterOrDigit() }}_$timestamp.txt"

        val header = "========================================\n" +
                "BRUCE IR - REJESTRATOR BLE GATT SNIFFER\n" +
                "Urządzenie: $deviceName\n" +
                "Adres MAC: $macAddress\n" +
                "Data: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                "========================================\n\n"

        val fullText = header + logContent

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Pomiary")
                }
                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(fullText.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "Zapisano log BLE w Dokumenty/Pomiary:\n$fileName", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (e: Exception) {
                Log.e("BruceBleLogger", "MediaStore error: ${e.message}")
            }
        }

        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Pomiary")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(fullText, Charsets.UTF_8)
            Toast.makeText(context, "Zapisano log BLE w Dokumenty/Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            val fallbackDir = File(context.getExternalFilesDir(null), "Pomiary").apply { if (!exists()) mkdirs() }
            val file = File(fallbackDir, fileName)
            file.writeText(fullText, Charsets.UTF_8)
            Toast.makeText(context, "Zapisano log w folderze Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
