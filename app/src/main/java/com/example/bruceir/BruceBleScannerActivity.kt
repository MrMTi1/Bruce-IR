package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

class BruceBleScannerActivity : AppCompatActivity() {

    private class ScannedDevice(
        val device: BluetoothDevice,
        val rssi: Int,
        val scanRecord: String?
    )

    private val devicesList = mutableListOf<ScannedDevice>()
    private lateinit var containerDevices: LinearLayout
    private lateinit var btnScan: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var masterRadarView: BruceBleRadarView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        if (map.values.all { it }) {
            startBleScan()
        } else {
            Toast.makeText(this, "Wymagane uprawnienia Bluetooth i Lokalizacji do skanowania", Toast.LENGTH_LONG).show()
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
        checkPermissionsAndScan()
    }

    override fun onResume() {
        super.onResume()
        if (::masterRadarView.isInitialized) masterRadarView.startSensors()
    }

    override fun onPause() {
        if (::masterRadarView.isInitialized) masterRadarView.stopSensors()
        super.onPause()
    }

    override fun onDestroy() {
        stopBleScan()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ROZUMIEM", null)
            .show()
    }

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
        titleStack.addView(text("MASTER BLE RADAR", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Skaner i Radar Zbliżeniowy BLE", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val btnInfo = MaterialButton(this).apply {
            text = "[?]"
            setOnClickListener {
                showInfoDialog(
                    "ℹ Skaner i Radar Zbliżeniowy BLE",
                    "Ten moduł w czasie rzeczywistym skanuje eter radiowy BLE, wykrywa wszystkie aktywne urządzenia w zasięgu (Bruce, LilyGO, Smart TV, opaski, beacony) oraz prezentuje je na interaktywnej tarczy radaru z kompasem.\n\nKliknięcie dowolnego urządzenia otwiera dedykowany radar zbliżeniowy do namierzenia w terenie!"
                )
            }
        }
        header.addView(btnInfo, LinearLayout.LayoutParams(dp(50), dp(46)).apply { marginEnd = dp(6) })

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Wykrywa urządzenia BLE w eterze i umożliwia ich precyzyjne namierzanie.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(12))
        })

        // Master Visual Radar View
        val radarCard = card()
        val radarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        masterRadarView = BruceBleRadarView(this)
        radarLayout.addView(masterRadarView, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
        radarCard.addView(radarLayout)
        root.addView(radarCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val actionCard = card()
        val actionLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        btnScan = MaterialButton(this).apply {
            text = "🔍 Rozpocznij Skanowanie BLE"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                if (isScanning) stopBleScan() else checkPermissionsAndScan()
            }
        }
        actionLayout.addView(btnScan, LinearLayout.LayoutParams(-1, -2))

        tvStatus = text("Status: Gotowy do skanowania", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(8), 0, 0)
        }
        actionLayout.addView(tvStatus)

        actionCard.addView(actionLayout)
        root.addView(actionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        root.addView(text("Wykryte Urządzenia BLE (Kliknij, aby połączyć / namierzyć)", 15f, Color.WHITE, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        containerDevices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(containerDevices)
    }

    private fun checkPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startBleScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            val existing = devicesList.find { it.device.address == device.address }
            if (existing == null) {
                devicesList.add(ScannedDevice(device, rssi, result.device.name))
                if (::masterRadarView.isInitialized) masterRadarView.rssi = rssi
                renderDevices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val device = result.device
                val rssi = result.rssi
                if (devicesList.none { it.device.address == device.address }) {
                    devicesList.add(ScannedDevice(device, rssi, result.device.name))
                }
            }
            if (results.isNotEmpty() && ::masterRadarView.isInitialized) {
                masterRadarView.rssi = results.maxOf { it.rssi }
            }
            renderDevices()
        }

        override fun onScanFailed(errorCode: Int) {
            tvStatus.text = "Status: Błąd skanowania (Kod: $errorCode)"
            stopBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth jest wyłączony w telefonie!", Toast.LENGTH_SHORT).show()
            return
        }

        devicesList.clear()
        renderDevices()
        isScanning = true
        btnScan.text = "⏹ Zatrzymaj Skanowanie"
        btnScan.setBackgroundColor(0xFFE53935.toInt())
        btnScan.setTextColor(Color.WHITE)
        tvStatus.text = "Status: Skanowanie eteru BLE na żywo..."

        try {
            adapter.bluetoothLeScanner?.startScan(scanCallback)
            handler.postDelayed({
                if (isScanning) stopBleScan()
            }, 20000)
        } catch (e: Exception) {
            tvStatus.text = "Status: Błąd startu skanowania: ${e.message}"
            stopBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        isScanning = false
        btnScan.text = "🔍 Rozpocznij Skanowanie BLE"
        btnScan.setBackgroundColor(0xFF43C59E.toInt())
        btnScan.setTextColor(Color.BLACK)
        tvStatus.text = "Status: Zatrzymano skanowanie (Wykryto ${devicesList.size} urządzeń)"

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun renderDevices() {
        containerDevices.removeAllViews()
        if (devicesList.isEmpty()) {
            containerDevices.addView(text("Brak wykrytych urządzeń BLE w pobliżu...\nKliknij 'Rozpocznij Skanowanie'.", 13f, 0xFF8290A2.toInt()).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            return
        }

        devicesList.sortedByDescending { it.rssi }.forEach { item ->
            val dCard = card()
            val dLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val name = if (item.device.name.isNullOrBlank()) "Nieznane urządzenie (Anonymous)" else item.device.name
            dLayout.addView(text(name, 15f, 0xFF43C59E.toInt(), true))
            dLayout.addView(text("MAC: ${item.device.address} | RSSI: ${item.rssi} dBm", 12f, 0xFF9EADBD.toInt()).apply {
                setPadding(0, dp(2), 0, dp(6))
            })

            val btnRowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnPairDevice = MaterialButton(this).apply {
                text = "🔑 Sparuj"
                setBackgroundColor(0xFF43C59E.toInt())
                setTextColor(Color.BLACK)
                setOnClickListener {
                    try {
                        BruceBleManager.connect(this@BruceBleScannerActivity, item.device.address)
                        val bonded = BruceBleManager.triggerBonding(this@BruceBleScannerActivity)
                        if (bonded) {
                            Toast.makeText(this@BruceBleScannerActivity, "Wysłano żądanie parowania dla $name!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@BruceBleScannerActivity, "Błąd parowania: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val btnGatt = MaterialButton(this).apply {
                text = "⚡ GATT Manager"
                setBackgroundColor(0xFF283545.toInt())
                setTextColor(Color.WHITE)
                setOnClickListener {
                    try {
                        BruceBleManager.connect(this@BruceBleScannerActivity, item.device.address)
                        val intent = Intent(this@BruceBleScannerActivity, BruceBleGattManagerActivity::class.java).apply {
                            putExtra("EXTRA_MAC", item.device.address)
                            putExtra("EXTRA_NAME", name)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@BruceBleScannerActivity, "Błąd łączenia: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val btnRadarTarget = MaterialButton(this).apply {
                text = "📡 Namierz"
                setBackgroundColor(0xFF00E5FF.toInt())
                setTextColor(Color.BLACK)
                setOnClickListener {
                    try {
                        val intent = Intent(this@BruceBleScannerActivity, BruceBleRadarActivity::class.java).apply {
                            putExtra("EXTRA_MAC", item.device.address)
                            putExtra("EXTRA_NAME", name)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@BruceBleScannerActivity, "Błąd startu radaru: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            btnRowActions.addView(btnPairDevice, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
            btnRowActions.addView(btnGatt, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            btnRowActions.addView(btnRadarTarget, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) })
            dLayout.addView(btnRowActions, LinearLayout.LayoutParams(-1, -2))

            dCard.addView(dLayout)
            containerDevices.addView(dCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
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
