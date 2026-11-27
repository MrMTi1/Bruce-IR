package com.example.bruceir

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.*

class DebugConsoleActivity : AppCompatActivity() {

    private lateinit var tvLogOutput: TextView

    companion object {
        private val logBuffer = StringBuilder()

        fun log(tag: String, message: String) {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val line = "[$time] [$tag]: $message\n"
            logBuffer.append(line)
            if (logBuffer.length > 20000) {
                logBuffer.delete(0, logBuffer.length - 15000)
            }
        }

        fun getLogs(): String = logBuffer.toString()
        fun clearLogs() { logBuffer.clear() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(100))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("LIVE DEBUG CONSOLE & SNIFFER", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Konsola Diagnostyczna BLE", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Podgląd komunikacji BLE z LilyGO, stanów połączenia i zdarzeń w czasie rzeczywistym.", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // Log Card
        val logCard = card()
        val logLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logLayout.addView(text("📄 Bufor Logów (Live Stream)", 14f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })

        tvLogOutput = text(if (logBuffer.isNotEmpty()) logBuffer.toString() else "Brak logów. Wykonaj akcję w module, aby zobaczyć pakiety...", 11f, 0xFFE0E0E0.toInt()).apply {
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scrollLog = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(320)).apply { topMargin = dp(4); bottomMargin = dp(8) }
            addView(tvLogOutput)
        }
        logLayout.addView(scrollLog)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnCopy = MaterialButton(this).apply {
            text = "📋 Kopiuj Logi"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("BruceIR Debug Logs", logBuffer.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@DebugConsoleActivity, "Skopiowano logi do schowka!", Toast.LENGTH_SHORT).show()
            }
        }
        val btnClear = MaterialButton(this).apply {
            text = "🗑️ Wyczyść"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                clearLogs()
                tvLogOutput.text = "Bufor wyczyszczony."
                Toast.makeText(this@DebugConsoleActivity, "Wyczyszczono logi", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(btnCopy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        btnRow.addView(btnClear, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        logLayout.addView(btnRow)

        logCard.addView(logLayout)
        root.addView(logCard)
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(0xFF141B25.toInt())
        strokeColor = 0xFF283545.toInt()
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
}
