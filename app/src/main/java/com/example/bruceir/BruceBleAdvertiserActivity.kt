package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.*

class BruceBleAdvertiserActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etDeviceName: EditText
    private lateinit var etUuid: EditText
    private lateinit var btnToggleAdv: MaterialButton

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        if (map.values.all { it }) {
            startAdvertisingInternal()
        } else {
            Toast.makeText(this, "Wymagane uprawnienie Bluetooth Advertise do nadawania", Toast.LENGTH_LONG).show()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            runOnUiThread {
                tvStatus.text = "Status: 🟢 Nadawanie BLE Aktywne w Eterze!"
                btnToggleAdv.text = "⏹ Zatrzymaj Nadajnik BLE"
                btnToggleAdv.setBackgroundColor(0xFFE53935.toInt())
                btnToggleAdv.setTextColor(Color.WHITE)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            runOnUiThread {
                tvStatus.text = "Status: ❌ Błąd nadawania BLE (Kod: $errorCode)"
                btnToggleAdv.text = "📡 Rozpocznij Nadawanie BLE"
                btnToggleAdv.setBackgroundColor(0xFF43C59E.toInt())
                btnToggleAdv.setTextColor(Color.BLACK)
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
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        buildScreen()
    }

    override fun onDestroy() {
        stopAdvertising()
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
        titleStack.addView(text("BLE BEACON & ADVERTISER", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Nadajnik & Beacon BLE", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Przekształca telefon w nadajnik Bluetooth LE (Rozgłasza nazwy, pakiety Beacon i usługi).", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        val advCard = card()
        val advLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        advLayout.addView(text("📡 Konfiguracja Pakietu Reklamowego", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etDeviceName = inputField("Nazwa urządzenia w eterze [np. Bruce_Node_01]")
        etDeviceName.setText("Bruce_Beacon_Node")

        etUuid = inputField("Usługa UUID [np. 0000180d-0000-1000-8000-00805f9b34fb]")
        etUuid.setText("0000180d-0000-1000-8000-00805f9b34fb")

        advLayout.addView(etDeviceName, verticalParams(dp(4)))
        advLayout.addView(etUuid, verticalParams(dp(4)))

        btnToggleAdv = MaterialButton(this).apply {
            text = "📡 Rozpocznij Nadawanie BLE"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                if (isAdvertising) stopAdvertising() else checkPermissionsAndStart()
            }
        }
        advLayout.addView(btnToggleAdv, verticalParams(dp(10)))

        tvStatus = text("Status: Gotowy do nadawania", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(8), 0, 0)
        }
        advLayout.addView(tvStatus)

        advCard.addView(advLayout)
        root.addView(advCard)
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startAdvertisingInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal() {
        val advertiser = bluetoothLeAdvertiser
        if (advertiser == null || bluetoothAdapter?.isEnabled == false) {
            Toast.makeText(this, "Nadawanie BLE nie jest obsługiwane przez ten telefon!", Toast.LENGTH_LONG).show()
            return
        }

        val name = etDeviceName.text.toString().trim()
        val uuidStr = etUuid.text.toString().trim()

        if (name.isNotEmpty()) {
            try {
                bluetoothAdapter?.name = name
            } catch (_: Exception) {}
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)

        try {
            if (uuidStr.isNotEmpty()) {
                dataBuilder.addServiceUuid(ParcelUuid(UUID.fromString(uuidStr)))
            }
        } catch (_: Exception) {}

        try {
            advertiser.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
        } catch (e: Exception) {
            tvStatus.text = "Status: Błąd startu: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!isAdvertising) return
        isAdvertising = false
        btnToggleAdv.text = "📡 Rozpocznij Nadawanie BLE"
        btnToggleAdv.setBackgroundColor(0xFF43C59E.toInt())
        btnToggleAdv.setTextColor(Color.BLACK)
        tvStatus.text = "Status: Zatrzymano nadawanie"

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
    }

    private fun inputField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF8290A2.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.button_glass_bg)
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

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
    }
}
