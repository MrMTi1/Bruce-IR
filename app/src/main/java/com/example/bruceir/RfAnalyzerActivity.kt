package com.example.bruceir

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

class RfAnalyzerActivity : AppCompatActivity() {

    private lateinit var graph: RfGraphView
    private lateinit var waterfall: WaterfallView
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    
    private val ranges = arrayOf(
        "300 - 348 MHz",
        "387 - 464 MHz",
        "779 - 928 MHz"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rf_analyzer)

        graph = findViewById(R.id.rfSpectrogram)
        waterfall = findViewById(R.id.rfWaterfall)
        val btnStart = findViewById<Button>(R.id.btnStartRfScan)
        val spnRange = findViewById<Spinner>(R.id.spnRfRange)

        spnRange.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ranges)

        btnStart.setOnClickListener {
            if (isScanning) {
                isScanning = false
                btnStart.text = "START"
            } else {
                isScanning = true
                btnStart.text = "STOP"
                startScanLoop(spnRange.selectedItemPosition)
            }
        }
    }

    private fun startScanLoop(rangeIdx: Int) {
        if (!isScanning) return

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        val user = prefs.getString("bruce_user", "admin") ?: "admin"
        val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        Thread {
            try {
                // Nowy hakerski endpoint: /rf/spectrum_data?range=0
                val url = "$baseUrl/rf/spectrum_data?range=$rangeIdx"
                val response = BruceUtils.downloadBinaryFile(url, user, pass)
                
                if (response != null) {
                    // RSSI to zwykle bajty (zrzut z rejestru CC1101)
                    val rssiData = IntArray(response.size) { response[it].toInt() - 256 } // Konwersja na dBm (uproszczona)
                    
                    runOnUiThread {
                        graph.updateData(rssiData)
                        waterfall.addRow(rssiData)
                    }
                }
            } catch (e: Exception) {}
            
            if (isScanning) {
                handler.postDelayed({ startScanLoop(rangeIdx) }, 100)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
    }
}
