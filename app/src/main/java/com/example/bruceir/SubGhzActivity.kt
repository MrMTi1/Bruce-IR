package com.example.bruceir

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class SubGhzActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetails: TextView
    private lateinit var btnClone: Button
    private var lastCapturedCommand: Command? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subghz)

        tvStatus = findViewById(R.id.tvScanStatus)
        tvDetails = findViewById(R.id.tvSignalDetails)
        btnClone = findViewById(R.id.btnCloneSave)

        findViewById<Button>(R.id.btnStartAutoScan).setOnClickListener {
            startAutoDetection()
        }

        btnClone.setOnClickListener {
            saveClonedCommand()
        }

        findViewById<Button>(R.id.btnSubGhzBack).setOnClickListener { finish() }
    }

    private fun startAutoDetection() {
        tvStatus.text = "Status: SCANNING (Frequency Hopping)"
        findViewById<ProgressBar>(R.id.pbScanning).visibility = View.VISIBLE
        
        Thread {
            // Bruce Firmware logic: Scan bands and send back first strong signal
            val url = "http://bruce.local/rf/autoscan"
            val response = BruceUtils.downloadFileContent(url, "admin", "bruce")
            
            runOnUiThread {
                findViewById<ProgressBar>(R.id.pbScanning).visibility = View.GONE
                if (response != null && response.contains("data:")) {
                    analyzeSignal(response)
                } else {
                    tvStatus.text = "Status: TIMEOUT / NO SIGNAL"
                }
            }
        }.start()
    }

    private fun analyzeSignal(rawResponse: String) {
        // Tu używamy procesora telefonu do dekodowania
        // Przykładowy format: Freq:433.92 Data:100 200 100 200...
        try {
            val freq = rawResponse.substringAfter("Freq:").substringBefore(" ").toDoubleOrNull() ?: 433.92
            val dataStr = rawResponse.substringAfter("Data:")
            val pattern = dataStr.trim().split(" ").map { it.toInt() }.toIntArray()
            
            // Inteligentna identyfikacja (uproszczona)
            val protocol = when {
                pattern.size == 25 -> "Came 12-bit (Fixed)"
                pattern.size > 100 -> "Rolling Code (Complex)"
                else -> "Unknown OOK/ASK"
            }

            tvStatus.text = "Status: SIGNAL CAPTURED!"
            tvDetails.text = "Frequency: $freq MHz\nProtocol: $protocol\nRaw Bits: ${pattern.size}"
            
            lastCapturedCommand = Command("CLONED_${System.currentTimeMillis()}", (freq * 1000).toInt(), pattern)
            btnClone.isEnabled = true
            
        } catch (e: Exception) {
            tvDetails.text = "Analysis Error: ${e.message}"
        }
    }

    private fun saveClonedCommand() {
        val cmd = lastCapturedCommand ?: return
        val allData = BruceUtils.loadAllData(this)
        var downloaded = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder
        if (downloaded == null) {
            downloaded = IrFolder("DOWNLOADED")
            allData.items.add(downloaded)
        }
        downloaded.items.add(0, cmd)
        BruceUtils.saveAllData(this, allData)
        Toast.makeText(this, "Cloned to DOWNLOADED folder!", Toast.LENGTH_SHORT).show()
        btnClone.isEnabled = false
    }
}
