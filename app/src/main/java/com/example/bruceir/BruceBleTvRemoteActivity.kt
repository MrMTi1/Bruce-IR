package com.example.bruceir

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.*

class BruceBleTvRemoteActivity : AppCompatActivity(), BruceBleHidRemoteEngine.StateListener {

    private lateinit var tvBrandStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleServer: MaterialButton
    private lateinit var btnPairClient: MaterialButton
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private var deviceName: String? = null
    private var detectedBrand: String = "UNIVERSAL_ANDROID_TV"
    private val handler = Handler(Looper.getMainLooper())

    private val pairingReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, 0)
                    tvStatus.text = "Status: 📡 Żądanie parowania z ${device?.name ?: device?.address} (Wariant: $variant)"
                    tvStatus.setTextColor(0xFFFFD54F.toInt())

                    try {
                        if (device != null) {
                            if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION || variant == 3) {
                                device.setPairingConfirmation(true)
                                Toast.makeText(this@BruceBleTvRemoteActivity, "Auto-zaakceptowano parowanie z TV!", Toast.LENGTH_SHORT).show()
                            } else {
                                device.setPin("0000".toByteArray())
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@BruceBleTvRemoteActivity, "Parowanie: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val targetMac = deviceAddress
                    if (targetMac.isNullOrEmpty() || device?.address == targetMac) {
                        when (bondState) {
                            BluetoothDevice.BOND_BONDING -> {
                                tvStatus.text = "Status: 📡 TV zaakceptował sygnał! Trwa parowanie BLE..."
                                tvStatus.setTextColor(0xFFFFD54F.toInt())
                            }
                            BluetoothDevice.BOND_BONDED -> {
                                tvStatus.text = "Status: 🟢 SPAROWANO POMYŚLNIE z ${device?.name ?: deviceName}! Pilot aktywny."
                                tvStatus.setTextColor(0xFF43C59E.toInt())
                                Toast.makeText(this@BruceBleTvRemoteActivity, "Sparowano z TV! Pilot jest aktywny.", Toast.LENGTH_LONG).show()
                                connectToTvClient()
                            }
                            BluetoothDevice.BOND_NONE -> {
                                tvStatus.text = "Status: 🔴 Brak parowania (Anulowano lub Odrzucono)"
                                tvStatus.setTextColor(0xFFE53935.toInt())
                            }
                        }
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Status: 🟢 Połączono z TV przez BLE! Odkrywanie usług..."
                    Toast.makeText(this@BruceBleTvRemoteActivity, "Połączono z TV przez BLE!", Toast.LENGTH_SHORT).show()
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Status: 🔴 Rozłączono z TV"
                    tvStatus.setTextColor(0xFFE53935.toInt())
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    tvStatus.text = "Status: 🟢 Usługi TV aktywne (${gatt.services.size} usług). Pilot gotowy!"
                    tvStatus.setTextColor(0xFF43C59E.toInt())
                    Toast.makeText(this@BruceBleTvRemoteActivity, "Gotowy do wysyłania komend!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        deviceAddress = intent.getStringExtra("EXTRA_MAC")
        deviceName = intent.getStringExtra("EXTRA_NAME") ?: "Smart TV"
        detectTvBrand(deviceName.orEmpty())

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(pairingReceiver, filter)

        BruceBleHidRemoteEngine.registerStateListener(this)

        buildScreen()

        if (!deviceAddress.isNullOrEmpty()) {
            connectToTvClient()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        BruceBleHidRemoteEngine.unregisterStateListener(this)
        try {
            unregisterReceiver(pairingReceiver)
        } catch (_: Exception) {}
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onServerStateChanged(running: Boolean, statusText: String) {
        runOnUiThread {
            tvStatus.text = "Status: $statusText"
            if (running) {
                tvStatus.setTextColor(0xFF43C59E.toInt())
                btnToggleServer.text = "⏹ Zatrzymaj Emulator Pilota BLE"
                btnToggleServer.setBackgroundColor(0xFFE53935.toInt())
                btnToggleServer.setTextColor(Color.WHITE)
            } else {
                btnToggleServer.text = "📡 Nadawaj jako Pilot BLE (Emulacja HID)"
                btnToggleServer.setBackgroundColor(0xFF43C59E.toInt())
                btnToggleServer.setTextColor(Color.BLACK)
            }
        }
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        runOnUiThread {
            @SuppressLint("MissingPermission")
            val name = if (!device.name.isNullOrBlank()) device.name else device.address
            tvStatus.text = "Status: 🟢 Połączono z: $name (MAC: ${device.address})"
            tvStatus.setTextColor(0xFF43C59E.toInt())
            Toast.makeText(this, "Połączono z $name!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        runOnUiThread {
            @SuppressLint("MissingPermission")
            val name = device.name ?: device.address
            tvStatus.text = "Status: ⚠️ TV / Klient rozłączony: $name"
            tvStatus.setTextColor(0xFFFFD54F.toInt())
        }
    }

    override fun onPairingEvent(message: String) {
        runOnUiThread {
            tvStatus.text = "Status: $message"
        }
    }

    private fun detectTvBrand(name: String) {
        val lower = name.lowercase(Locale.getDefault())
        detectedBrand = when {
            lower.contains("samsung") -> "SAMSUNG_SMART_TV"
            lower.contains("lg") || lower.contains("webos") -> "LG_WEBOS_TV"
            lower.contains("sony") || lower.contains("bravia") -> "SONY_ANDROID_TV"
            lower.contains("xiaomi") || lower.contains("mibox") || lower.contains("mitv") -> "XIAOMI_ANDROID_TV"
            lower.contains("apple") -> "APPLE_TV"
            else -> "ANDROID_GOOGLE_TV"
        }
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
        titleStack.addView(text("BLE TV REMOTE ENGINE", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Bezprzewodowy Pilot BLE", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        // Brand & Pairing Options Card
        val brandCard = card()
        val brandLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brandNameFormatted = when (detectedBrand) {
            "SAMSUNG_SMART_TV" -> "📺 Samsung Smart TV (Tizen / BLE Remote)"
            "LG_WEBOS_TV" -> "📺 LG webOS TV (BLE Magic Remote)"
            "SONY_ANDROID_TV" -> "📺 Sony Bravia (Android / Google TV BLE)"
            "XIAOMI_ANDROID_TV" -> "📺 Xiaomi Mi TV / Box (Android TV BLE)"
            "APPLE_TV" -> "🍎 Apple TV (BLE Remote)"
            else -> "📺 Uniwersalny Android / Google TV BLE"
        }
        tvBrandStatus = text("Wykryty profil: $brandNameFormatted", 15f, 0xFF43C59E.toInt(), true)
        brandLayout.addView(tvBrandStatus)

        tvStatus = text("Status: Oczekiwanie na wybór trybu parowania BLE...", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(4), 0, dp(6))
        }
        brandLayout.addView(tvStatus)

        // Button 1: Emulacja Pilota BLE (Start GattServer & Advertiser)
        btnToggleServer = MaterialButton(this).apply {
            text = if (BruceBleHidRemoteEngine.isServerRunning) "⏹ Zatrzymaj Emulator Pilota BLE" else "📡 Nadawaj jako Pilot BLE (Wyszukaj w TV)"
            setBackgroundColor(if (BruceBleHidRemoteEngine.isServerRunning) 0xFFE53935.toInt() else 0xFF43C59E.toInt())
            setTextColor(if (BruceBleHidRemoteEngine.isServerRunning) Color.WHITE else Color.BLACK)
            setOnClickListener {
                if (BruceBleHidRemoteEngine.isServerRunning) {
                    BruceBleHidRemoteEngine.stopRemoteServer()
                } else {
                    val remoteName = when (detectedBrand) {
                        "SAMSUNG_SMART_TV" -> "Samsung Smart Remote"
                        "LG_WEBOS_TV" -> "LG Magic Remote"
                        else -> "Bruce Smart Remote"
                    }
                    val ok = BruceBleHidRemoteEngine.startRemoteServer(this@BruceBleTvRemoteActivity, remoteName)
                    if (ok) {
                        Toast.makeText(this@BruceBleTvRemoteActivity, "Uruchomiono nadawanie pilota! Wyszukaj urządzenia Bluetooth w TV.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        brandLayout.addView(btnToggleServer, verticalParams(dp(4)))

        // Button 2: Połącz z MAC TV bezpośrednio
        btnPairClient = MaterialButton(this).apply {
            text = "🔑 Wybierz TV w Skanerze BLE"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { pairDeviceDirectly() }
        }
        brandLayout.addView(btnPairClient, verticalParams(dp(4)))

        // Button 3: Uruchom Tryb Parowania na LilyGO (Emulacja trzymania 2 klawiszy OK + BACK)
        val btnPairLilyGo = MaterialButton(this).apply {
            text = "🤝 Wymuś Tryb Parowania z TV (LilyGO BLE)"
            setBackgroundColor(0xFF1E88E5.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@BruceBleTvRemoteActivity, "remote pair")
                Toast.makeText(this@BruceBleTvRemoteActivity, "Uruchomiono tryb parowania na LilyGO! TV powienien wykryć nowy pilot.", Toast.LENGTH_LONG).show()
            }
        }
        brandLayout.addView(btnPairLilyGo, verticalParams(dp(4)))

        brandCard.addView(brandLayout)
        root.addView(brandCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Remote UI Card
        val remoteCard = card()
        val remoteLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Top Row: Power & Mute
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPower = MaterialButton(this).apply {
            text = "⏻ POWER"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.POWER) }
        }
        val btnMute = MaterialButton(this).apply {
            text = "🔇 MUTE"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.MUTE) }
        }
        topRow.addView(btnPower, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        topRow.addView(btnMute, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        remoteLayout.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // D-PAD NAVIGATOR (UP, DOWN, LEFT, RIGHT, OK)
        val dpadCard = card().apply { setCardBackgroundColor(0xFF0F141D.toInt()) }
        val dpadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val btnUp = MaterialButton(this).apply {
            text = "▲"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.DPAD_UP) }
        }
        dpadLayout.addView(btnUp, LinearLayout.LayoutParams(dp(70), dp(48)))

        val middleDpad = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnLeft = MaterialButton(this).apply {
            text = "◀"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.DPAD_LEFT) }
        }
        val btnOk = MaterialButton(this).apply {
            text = "OK"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.SELECT) }
        }
        val btnRight = MaterialButton(this).apply {
            text = "▶"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.DPAD_RIGHT) }
        }
        middleDpad.addView(btnLeft, LinearLayout.LayoutParams(dp(70), dp(48)))
        middleDpad.addView(btnOk, LinearLayout.LayoutParams(dp(80), dp(54)).apply { marginStart = dp(6); marginEnd = dp(6) })
        middleDpad.addView(btnRight, LinearLayout.LayoutParams(dp(70), dp(48)))
        dpadLayout.addView(middleDpad, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })

        val btnDown = MaterialButton(this).apply {
            text = "▼"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.DPAD_DOWN) }
        }
        dpadLayout.addView(btnDown, LinearLayout.LayoutParams(dp(70), dp(48)))

        dpadCard.addView(dpadLayout)
        remoteLayout.addView(dpadCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // Navigation & Volume Controls
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnBack = MaterialButton(this).apply {
            text = "↩ BACK"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.BACK) }
        }
        val btnHome = MaterialButton(this).apply {
            text = "🏠 HOME"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.HOME) }
        }
        navRow.addView(btnBack, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        navRow.addView(btnHome, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        remoteLayout.addView(navRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnVolUp = MaterialButton(this).apply {
            text = "🔊 VOL +"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.VOL_UP) }
        }
        val btnVolDown = MaterialButton(this).apply {
            text = "🔉 VOL -"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.VOL_DOWN) }
        }
        volRow.addView(btnVolUp, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        volRow.addView(btnVolDown, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        remoteLayout.addView(volRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Quick App Launchers (Netflix / YouTube)
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnNetflix = MaterialButton(this).apply {
            text = "🎬 NETFLIX"
            setBackgroundColor(0xFFE50914.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.NETFLIX) }
        }
        val btnYoutube = MaterialButton(this).apply {
            text = "▶ YOUTUBE"
            setBackgroundColor(0xFFFF0000.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendKey(BruceBleHidRemoteEngine.ConsumerKeys.YOUTUBE) }
        }
        appRow.addView(btnNetflix, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        appRow.addView(btnYoutube, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        remoteLayout.addView(appRow, LinearLayout.LayoutParams(-1, -2))

        remoteCard.addView(remoteLayout)
        root.addView(remoteCard)
    }

    @SuppressLint("MissingPermission")
    private fun pairDeviceDirectly() {
        val address = deviceAddress
        if (address.isNullOrEmpty()) {
            Toast.makeText(this, "Otwieram skaner BLE w celu wyboru TV...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, BruceBleScannerActivity::class.java)
            startActivity(intent)
            return
        }
        try {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                val success = device.createBond()
                if (success) {
                    tvStatus.text = "Status: 📡 Inicjowanie parowania BLE z $deviceName... Sprawdź TV."
                    tvStatus.setTextColor(0xFFFFD54F.toInt())
                }
            } else {
                tvStatus.text = "Status: 🟢 Telewizor $deviceName jest już sparowany w systemie!"
                tvStatus.setTextColor(0xFF43C59E.toInt())
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd parowania: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToTvClient() {
        val address = deviceAddress ?: return
        if (bluetoothAdapter == null) return
        try {
            val device = bluetoothAdapter!!.getRemoteDevice(address)
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            tvStatus.text = "Status: Błąd połączenia z TV: ${e.message}"
        }
    }

    private fun sendKey(consumerKeycode: Int) {
        // Tryb 1: Jeśli uruchomiony jest emulator pilota BLE (GattServer/Peripheral), wyślij przez serwer HID
        if (BruceBleHidRemoteEngine.isServerRunning) {
            val sent = BruceBleHidRemoteEngine.sendConsumerKey(consumerKeycode)
            if (sent) {
                Toast.makeText(this, "Wysłano (HID Server): 0x${Integer.toHexString(consumerKeycode)}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Tryb 1.5: Przekaż przez LilyGO BLE (BruceBleIrLink) - dedykowany moduł remote
        BruceBleIrLink.sendCustomCommand(this, "remote press $consumerKeycode")

        // Tryb 2: Jeśli połączono jako GATT Client z TV
        if (bluetoothGatt != null) {
            val sent = BruceBleManager.sendTvKeycode(consumerKeycode)
            if (sent) {
                Toast.makeText(this, "Wysłano (GATT Client): 0x${Integer.toHexString(consumerKeycode)}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Tryb 3: Rezerwowo przez BruceBleManager / LilyGO
        BruceBleManager.sendTvKeycode(consumerKeycode)
        Toast.makeText(this, "Wysłano przez LilyGO BLE: 0x${Integer.toHexString(consumerKeycode)}", Toast.LENGTH_SHORT).show()
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
