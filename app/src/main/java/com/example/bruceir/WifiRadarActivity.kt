package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class WifiRadarActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var radarCanvas: RadarView
    private lateinit var wifiManager: WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val accessPoints = mutableMapOf<String, Int>() // SSID -> RSSI
    private var isScanning = false

    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: true
            if (success) {
                val results = wifiManager.scanResults
                accessPoints.clear()
                results.forEach { ap ->
                    val name = if (ap.SSID.isNullOrBlank()) "(Ukryta sieć)" else ap.SSID
                    accessPoints[name] = ap.level
                }
                radarCanvas.updatePoints(accessPoints)
                tvStatus.text = "Status: Znaleziono ${accessPoints.size} sieci Wi-Fi w pobliżu"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, filter)

        buildScreen()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 15))
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("WIFI RADAR & RSSI", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Radar Skaner Wi-Fi", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        tvStatus = text("Status: Oczekiwanie na skanowanie Wi-Fi...", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(tvStatus)

        // Radar View Card
        val radarCard = card()
        val radarLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radarLayout.addView(text("📡 Skaner Odległości i Siły Sygnału AP", 13f, 0xFF43C59E.toInt(), true).apply {
            setPadding(0, 0, 0, dp(6))
        })

        radarCanvas = RadarView(this)
        radarLayout.addView(radarCanvas, LinearLayout.LayoutParams(-1, dp(300)))
        radarCard.addView(radarLayout)
        root.addView(radarCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val btnScan = MaterialButton(this).apply {
            text = "📡 Skanuj Sieci Wi-Fi"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { triggerWifiScan() }
        }
        root.addView(btnScan)

        setContentView(root)
        triggerWifiScan()
    }

    @SuppressLint("MissingPermission")
    private fun triggerWifiScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }
        val success = wifiManager.startScan()
        if (success) {
            tvStatus.text = "Status: Skanowanie sieci Wi-Fi..."
        } else {
            tvStatus.text = "Status: Skanowanie zajęte (spróbuj ponownie za chwilę)"
        }
    }

    private class RadarView(context: Context) : View(context) {
        private val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF283545.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF283545.toInt()
            strokeWidth = 2f
        }
        private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9EADBD.toInt()
            textSize = 28f
        }
        private val paintPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF43C59E.toInt()
            style = Paint.Style.FILL
        }
        private var points = mapOf<String, Int>()

        fun updatePoints(newPoints: Map<String, Int>) {
            points = newPoints
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFF0F141D.toInt())
            val cx = width / 2f
            val cy = height / 2f
            val radius = Math.min(cx, cy) - 40f

            // Draw radar circles
            canvas.drawCircle(cx, cy, radius, paintCircle)
            canvas.drawCircle(cx, cy, radius * 0.66f, paintCircle)
            canvas.drawCircle(cx, cy, radius * 0.33f, paintCircle)

            // Cross lines
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paintLine)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paintLine)

            // Draw AP points based on RSSI (-30 = close, -90 = far)
            var angle = 0.0
            val angleStep = if (points.isNotEmpty()) 360.0 / points.size else 0.0

            points.forEach { (ssid, rssi) ->
                val normalizedRssi = (rssi.coerceIn(-90, -30) + 90) / 60.0 // 0 to 1
                val dist = radius * (1.0 - normalizedRssi)
                val rad = Math.toRadians(angle)
                val x = (cx + dist * Math.cos(rad)).toFloat()
                val y = (cy + dist * Math.sin(rad)).toFloat()

                canvas.drawCircle(x, y, 12f, paintPoint)
                canvas.drawText("$ssid (${rssi}dBm)", x + 15f, y + 5f, paintText)
                angle += angleStep
            }
        }
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
