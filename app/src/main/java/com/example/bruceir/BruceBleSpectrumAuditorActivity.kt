package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.*

class BruceBleSpectrumAuditorActivity : AppCompatActivity() {

    private lateinit var tvPacketsRate: TextView
    private lateinit var tvSaturation: TextView
    private lateinit var pbSaturation: ProgressBar
    private lateinit var tvAdvice: TextView
    private lateinit var containerTopTalkers: LinearLayout
    private lateinit var btnToggleAudit: MaterialButton

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isAuditing = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAuditStart = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val allGranted = granted.values.all { it }
        if (allGranted && pendingAuditStart) {
            startAudit()
        } else if (!allGranted) {
            tvAdvice.text = "Brak uprawnień Bluetooth. Audyt pasma wymaga BLUETOOTH_SCAN/CONNECT."
        }
        pendingAuditStart = false
    }

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private var packetCountWindow = 0
    private var packetsPerSecond = 0
    private val devicePacketCounts = mutableMapOf<String, Int>()
    private val deviceNames = mutableMapOf<String, String>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            val name = result.device.name ?: "Nieznany nadajnik"

            packetCountWindow++
            devicePacketCounts[addr] = (devicePacketCounts[addr] ?: 0) + 1
            deviceNames[addr] = name
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val addr = result.device.address
                val name = result.device.name ?: "Nieznany nadajnik"
                packetCountWindow++
                devicePacketCounts[addr] = (devicePacketCounts[addr] ?: 0) + 1
                deviceNames[addr] = name
            }
        }
    }

    private val calcRunnable = object : Runnable {
        override fun run() {
            if (isAuditing) {
                packetsPerSecond = packetCountWindow
                packetCountWindow = 0

                updateAuditUi()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        buildScreen()
        startAudit()
    }

    override fun onDestroy() {
        stopAudit()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(130))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("RF & BLE AUDYTOR PASMA", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Analizator Zakłóceń 2.4GHz", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Pasywna analiza nasycenia pasma 2.4 GHz, natężenia pakietów i źródeł zakłóceń.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // Metric Card
        val metricCard = card()
        val metricLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tvPacketsRate = text("Prędkość transmisji: 0 pakietów / sek", 18f, 0xFF43C59E.toInt(), true)
        metricLayout.addView(tvPacketsRate)

        tvSaturation = text("Nasycenie pasma 2.4 GHz: 0%", 14f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        metricLayout.addView(tvSaturation)

        pbSaturation = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        metricLayout.addView(pbSaturation, LinearLayout.LayoutParams(-1, dp(18)).apply {
            bottomMargin = dp(10)
        })

        tvAdvice = text("Rekomendacja: Skanowanie eteru...", 12f, 0xFF00E5FF.toInt())
        metricLayout.addView(tvAdvice)

        btnToggleAudit = MaterialButton(this).apply {
            text = "⏹ Zatrzymaj Analizator"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isAuditing) stopAudit() else startAudit()
            }
        }
        metricLayout.addView(btnToggleAudit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        metricCard.addView(metricLayout)
        root.addView(metricCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Top Talkers Container
        root.addView(text("Najbardziej Aktywne Nadajniki w Eterze", 16f, Color.WHITE, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        containerTopTalkers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(containerTopTalkers)
    }

    @SuppressLint("MissingPermission")
    private fun startAudit() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth jest wyłączony!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBlePermissions()) {
            pendingAuditStart = true
            permissionLauncher.launch(requiredBlePermissions())
            return
        }

        isAuditing = true
        btnToggleAudit.text = "⏹ Zatrzymaj Analizator"
        btnToggleAudit.setBackgroundColor(0xFFE53935.toInt())
        btnToggleAdvTextColor(Color.WHITE)

        try {
            adapter.bluetoothLeScanner?.startScan(scanCallback)
            handler.post(calcRunnable)
        } catch (e: Exception) {
            tvAdvice.text = "Błąd audytu: ${e.message}"
        }
    }

    private fun btnToggleAdvTextColor(color: Int) {
        btnToggleAudit.setTextColor(color)
    }

    @SuppressLint("MissingPermission")
    private fun stopAudit() {
        isAuditing = false
        btnToggleAudit.text = "📡 Rozpocznij Audyt Pasma"
        btnToggleAudit.setBackgroundColor(0xFF43C59E.toInt())
        btnToggleAudit.setTextColor(Color.BLACK)
        handler.removeCallbacks(calcRunnable)

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private fun updateAuditUi() {
        val pps = packetsPerSecond
        val saturationPercent = (pps * 2).coerceIn(0, 100)

        pbSaturation.progress = saturationPercent
        tvPacketsRate.text = "Prędkość transmisji: $pps pakietów / sek"
        tvSaturation.text = "Nasycenie pasma 2.4 GHz: $saturationPercent%"

        tvAdvice.text = when {
            saturationPercent >= 75 -> "⚠️ OSTRZEŻENIE: Pasmo 2.4GHz wysoce przesycone! Możliwe zakłócenia połączeń Wi-Fi / BLE."
            saturationPercent >= 40 -> "ℹ UMIARKOWANE: Średnie obciążenie eteru radiowego."
            else -> "🟢 CZYSTO: Niskie obciążenie eteru, idealne warunki dla transmisji IR/RF/BLE."
        }

        renderTopTalkers()
    }

    private fun renderTopTalkers() {
        containerTopTalkers.removeAllViews()
        val sorted = devicePacketCounts.entries.sortedByDescending { it.value }.take(5)

        if (sorted.isEmpty()) {
            containerTopTalkers.addView(text("Brak aktywności...", 12f, 0xFF9EADBD.toInt()))
            return
        }

        sorted.forEach { entry ->
            val mac = entry.key
            val name = deviceNames[mac] ?: "Nieznany nadajnik"
            val count = entry.value

            val tCard = card()
            val tLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            tLayout.addView(text(name, 14f, 0xFF43C59E.toInt(), true))
            tLayout.addView(text("MAC: $mac | Wykryte pakiety: $count", 11f, 0xFF9EADBD.toInt()).apply {
                setPadding(0, dp(2), 0, 0)
            })

            tCard.addView(tLayout)
            containerTopTalkers.addView(tCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
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
