package com.example.bruceir

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val etIp = findViewById<EditText>(R.id.etLoginIp)
        val etUser = findViewById<EditText>(R.id.etLoginUser)
        val etPass = findViewById<EditText>(R.id.etLoginPass)
        val btnTest = findViewById<Button>(R.id.btnLoginTest)
        val pb = findViewById<ProgressBar>(R.id.pbLogin)

        // Load existing
        etIp.setText(prefs.getString("bruce_url", "http://bruce.local"))
        etUser.setText(prefs.getString("bruce_user", "admin"))
        etPass.setText(prefs.getString("bruce_pass", "bruce"))

        btnTest.setOnClickListener {
            val url = etIp.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()

            if (url.isEmpty()) return@setOnClickListener

            pb.visibility = View.VISIBLE
            btnTest.isEnabled = false

            Thread {
                val formattedUrl = if (url.startsWith("http")) url else "http://$url"
                val pingUrl = if (formattedUrl.endsWith("/")) "${formattedUrl}ping" else "$formattedUrl/ping"
                
                val success = try {
                    val conn = URL(pingUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.responseCode == 200
                } catch (e: Exception) { false }

                runOnUiThread {
                    pb.visibility = View.GONE
                    btnTest.isEnabled = true
                    if (success) {
                        prefs.edit()
                            .putString("bruce_url", formattedUrl)
                            .putString("bruce_user", user)
                            .putString("bruce_pass", pass)
                            .apply()
                        Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                        finish() // Go back to main
                    } else {
                        Toast.makeText(this, R.string.login_fail, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }
}
