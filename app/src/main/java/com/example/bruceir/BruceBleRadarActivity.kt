package com.example.bruceir

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.*

class BruceBleRadarActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var pbRssi: ProgressBar
    private lateinit var tvDeviceName: TextView
    private lateinit var btnToggleRadar: MaterialButton
    private lateinit var radarView: BruceBleRadarView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var targetAddress: String? = null
    private var targetName: String? = null
    private var isRadarActive = false
    private var toneGenerator: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRadarStart = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val allGranted = granted.values.all { it }
        if (allGranted && pendingRadarStart) {
            startRadar()
        } else if (!allGranted) {
            tvStatus.text = "Brak uprawnień Bluetooth. Skanowanie BLE wymaga BLUETOOTH_SCAN/CONNECT."
        }
        pendingRadarStart = false
    }

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    private var lastRssi = -100

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.address == targetAddress) {
                updateRssi(result.rssi)
            }
        }
    }

    private val audioPulseRunnable = object : Runnable {
        override fun run() {
            if (isRadarActive) {
                playAudioPulse()
                val delayMs = calculatePulseDelay(lastRssi)
                handler.postDelayed(this, delayMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        targetAddress = intent.getStringExtra("EXTRA_MAC")
        targetName = intent.getStringExtra("EXTRA_NAME") ?: "Wybrane Urządzenie BLE"

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        } catch (_: Exception) {}

        buildScreen()
        startRadar()
    }

    override fun onResume() {
        super.onResume()
        if (::radarView.isInitialized) radarView.startSensors()
    }

    override fun onPause() {
        if (::radarView.isInitialized) radarView.stopSensors()
        super.onPause()
    }

    override fun onDestroy() {
        stopRadar()
        toneGenerator?.release()
        toneGenerator = null
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
        titleStack.addView(text("RADAR BLE & LOKALIZATOR", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Wykrywacz Sygnału BLE", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        tvDeviceName = text(targetName ?: "Urządzenie BLE", 16f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(tvDeviceName)
        root.addView(text("MAC: $targetAddress", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, 0, 0, dp(14))
        })

        // Radar Card
        val radarCard = card()
        val radarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        tvDistance = text("Szacowana odległość:\n~ -- m", 22f, 0xFF43C59E.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        radarLayout.addView(tvDistance)

        radarView = BruceBleRadarView(this)
        radarLayout.addView(radarView, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            topMargin = dp(6)
            bottomMargin = dp(10)
        })

        pbRssi = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
        }
        radarLayout.addView(pbRssi, LinearLayout.LayoutParams(-1, dp(20)).apply {
            topMargin = dp(6)
            bottomMargin = dp(14)
        })

        tvStatus = text("Status: Skanowanie i pomiar mocy sygnału...", 12f, 0xFF9EADBD.toInt()).apply {
            gravity = Gravity.CENTER
        }
        radarLayout.addView(tvStatus)

        btnToggleRadar = MaterialButton(this).apply {
            text = "⏹ Zatrzymaj Radar"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isRadarActive) stopRadar() else startRadar()
            }
        }
        radarLayout.addView(btnToggleRadar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        radarCard.addView(radarLayout)
        root.addView(radarCard)
    }

    private fun startRadar() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth jest wyłączony!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBlePermissions()) {
            pendingRadarStart = true
            permissionLauncher.launch(requiredBlePermissions())
            return
        }

        isRadarActive = true
        btnToggleRadar.text = "⏹ Zatrzymaj Radar"
        btnToggleRadar.setBackgroundColor(0xFFE53935.toInt())
        btnToggleRadar.setTextColor(Color.WHITE)

        try {
            adapter.bluetoothLeScanner?.startScan(scanCallback)
            handler.post(audioPulseRunnable)
        } catch (e: Exception) {
            tvStatus.text = "Błąd startu radaru: ${e.message}"
        }
    }

    private fun stopRadar() {
        isRadarActive = false
        btnToggleRadar.text = "📡 Uruchom Radar"
        btnToggleRadar.setBackgroundColor(0xFF43C59E.toInt())
        btnToggleRadar.setTextColor(Color.BLACK)
        handler.removeCallbacks(audioPulseRunnable)

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private fun updateRssi(rssi: Int) {
        lastRssi = rssi
        val normalizedProgress = (100 + rssi).coerceIn(5, 100)
        val estimatedMeters = Math.pow(10.0, (-59.0 - rssi) / 20.0)

        runOnUiThread {
            pbRssi.progress = normalizedProgress
            if (::radarView.isInitialized) radarView.rssi = rssi
            val statusCategory = when {
                rssi >= -60 -> "🟢 Bardzo blisko! (< 1m)"
                rssi >= -75 -> "🟡 Średni zasięg (1-3m)"
                rssi >= -90 -> "🟠 Daleko (> 3m)"
                else -> "🔴 Sygnał skrajnie słaby"
            }
            tvDistance.text = String.format(Locale.getDefault(), "Szacowana odległość:\n~ %.1f m\n(%s)", estimatedMeters, statusCategory)
            tvStatus.text = "RSSI: $rssi dBm | Moc sygnału: $normalizedProgress%"
        }
    }

    private fun calculatePulseDelay(rssi: Int): Long {
        return when {
            rssi >= -60 -> 150L
            rssi >= -75 -> 400L
            rssi >= -90 -> 800L
            else -> 1500L
        }
    }

    private fun playAudioPulse() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (_: Exception) {}
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
