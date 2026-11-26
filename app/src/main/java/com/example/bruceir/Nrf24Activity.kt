package com.example.bruceir

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Nrf24Activity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var spectrumView: SpectrumCanvas
    private lateinit var etJamChannel: EditText
    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()

        BruceBleIrLink.setListener { output ->
            if (output.contains("NRF24_SPECTRUM:", ignoreCase = true)) {
                val bits = output.substringAfter(":").trim()
                val realData = FloatArray(128)
                for (i in 0 until Math.min(128, bits.length)) {
                    realData[i] = if (bits[i] == '1') 0.9f else 0.05f
                }
                runOnUiThread {
                    spectrumView.updateData(realData)
                    tvStatus.text = "Status: Transmisja na żywo pasma 2.4 GHz z radia LilyGO NRF24"
                }
            }
        }
    }

    override fun onDestroy() {
        BruceBleIrLink.setListener(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(80))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("NRF24 2.4GHz SPECTRUM & AUDIT", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Analizator Pasma 2.4 GHz", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        tvStatus = text("Status: Gotowy do pomiaru pasma 2.4 GHz przez LilyGO / NRF24", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(tvStatus)

        // 1. SPECTRUM ANALYZER WATERFALL CARD
        val specCard = card()
        val specLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        specLayout.addView(text("📊 Analizator 128 Kanałów (2400 MHz - 2525 MHz)", 14f, 0xFF43C59E.toInt(), true).apply {
            setPadding(0, 0, 0, dp(6))
        })

        spectrumView = SpectrumCanvas(this)
        specLayout.addView(spectrumView, LinearLayout.LayoutParams(-1, dp(180)))

        val btnToggleScan = MaterialButton(this).apply {
            text = "📡 Skanuj Pasmo 2.4 GHz (Live)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                isScanning = !isScanning
                if (isScanning) {
                    text = "⏹ Zatrzymaj Skaner"
                    setBackgroundColor(0xFFE53935.toInt())
                    setTextColor(Color.WHITE)
                    startSpectrumScan()
                } else {
                    text = "📡 Skanuj Pasmo 2.4 GHz (Live)"
                    setBackgroundColor(0xFF43C59E.toInt())
                    setTextColor(Color.BLACK)
                }
            }
        }
        specLayout.addView(btnToggleScan, verticalParams(dp(8)))
        specCard.addView(specLayout)
        root.addView(specCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. NRF24 JAMMER & IMMUNITY TEST
        val jamCard = card()
        val jamLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        jamLayout.addView(text("⚡ Test Odporności RF 2.4 GHz (Carrier Jammer)", 14f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })

        etJamChannel = EditText(this).apply {
            hint = "Kanał (0-127) [np. 6 lub 11]"
            setText("6")
            setHintTextColor(0xFF748397.toInt())
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        jamLayout.addView(etJamChannel, verticalParams(dp(4)))

        val btnStartJammer = MaterialButton(this).apply {
            text = "⚡ Uruchom Test Fali Nośnej na Kanał"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                val ch = etJamChannel.text.toString().toIntOrNull() ?: 6
                BruceBleIrLink.sendCustomCommand(this@Nrf24Activity, "nrf jam $ch")
                Toast.makeText(this@Nrf24Activity, "Wysłano rozkaz NRF Jammer dla kanału $ch", Toast.LENGTH_SHORT).show()
            }
        }
        jamLayout.addView(btnStartJammer, verticalParams(dp(6)))

        jamCard.addView(jamLayout)
        root.addView(jamCard)
    }

    private fun startSpectrumScan() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isScanning) {
                BruceBleIrLink.sendCustomCommand(this@Nrf24Activity, "nrf scan")
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private class SpectrumCanvas(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF43C59E.toInt()
            style = Paint.Style.FILL
        }
        private var data = FloatArray(128)

        fun updateData(newData: FloatArray) {
            data = newData
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFF0F141D.toInt())
            val barWidth = width.toFloat() / 128f
            for (i in 0 until 128) {
                val h = data[i] * height.toFloat()
                canvas.drawRect(
                    i * barWidth,
                    height.toFloat() - h,
                    (i + 1) * barWidth - 1f,
                    height.toFloat(),
                    paint
                )
            }
        }
    }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
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
