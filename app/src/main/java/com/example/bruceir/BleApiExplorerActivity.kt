package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Samodzielny eksplorator BLE API:
 *  - ciągłe skanowanie BLE (BluetoothLeScanner, LOW_LATENCY) z agregacją reklam per adres,
 *  - własny, odporny na błędy parser surowych struktur AD (scanRecord.bytes),
 *  - niezależny klient GATT (własny BluetoothGattCallback) z drzewem usług/charakterystyk/deskryptorów,
 *  - odczyt/zapis/notyfikacje (CCCD), negocjacja MTU, log zdarzeń,
 *  - eksport migawki skanu i drzewa GATT do JSON (CreateDocument).
 *
 * Klasa nie używa singletona BruceBleManager (jest on zajęty przez inne ekrany).
 */
class BleApiExplorerActivity : AppCompatActivity() {

    // -----------------------------------------------------------------------------------------
    // Modele danych
    // -----------------------------------------------------------------------------------------

    private data class AdvRecord(
        val type: Int,
        val typeName: String,
        val valueHex: String,
        val detail: String
    )

    private class AdvParse(
        val flags: Int?,
        val flagsDecoded: String,
        val localName: String?,
        val localNameComplete: Boolean?,
        val txPower: Int?,
        val appearance: Int?,
        val appearanceName: String?,
        val serviceUuids: List<String>,
        val serviceData: List<Pair<String, String>>,
        val manufacturerData: List<Pair<Int, String>>,
        val unknownRecords: List<String>,
        val rawHex: String,
        val records: List<AdvRecord>
    ) {
        companion object {
            val EMPTY = AdvParse(
                null, "", null, null, null, null, null,
                emptyList(), emptyList(), emptyList(), emptyList(), "", emptyList()
            )
        }
    }

    private class DeviceEntry(val address: String) {
        var device: BluetoothDevice? = null
        var name: String? = null
        var addressType: String = "—"
        var deviceType: String = "—"
        var connectable: Boolean? = null
        var advCount: Int = 0
        var firstSeen: Long = 0L
        var lastSeen: Long = 0L
        var rssiLast: Int = 0
        var rssiMin: Int = 0
        var rssiMax: Int = 0
        var rssiSum: Long = 0L
        val rssiHistory: ArrayDeque<Int> = ArrayDeque()
        var ad: AdvParse = AdvParse.EMPTY

        val rssiAvg: Int
            get() = if (advCount <= 0) 0 else (rssiSum / advCount).toInt()

        fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: "(bez nazwy)"
        fun hasName(): Boolean = name?.isNotBlank() == true
    }

    private data class LastValue(
        val hex: String,
        val text: String,
        val source: String,
        val timeMs: Long
    )

    private data class GattLogEntry(val timeMs: Long, val message: String, val clock: String)

    private enum class SortMode { RSSI, NAME }

    private enum class PendingAction { NONE, SCAN, CONNECT }

    private enum class CccdMode { OFF, NOTIFY, INDICATE }

    // -----------------------------------------------------------------------------------------
    // Stałe, kolory, UUID
    // -----------------------------------------------------------------------------------------

    companion object {
        private const val REFRESH_INTERVAL_MS = 450L
        private const val RSSI_HISTORY_LIMIT = 40
        private const val GATT_LOG_LIMIT = 200
        private const val GATT_OP_TIMEOUT_MS = 6000L
        private const val CONNECT_TIMEOUT_MS = 12000L
        private const val MTU_FALLBACK_MS = 2000L
        private const val MAX_RENDERED_DEVICES = 40
        private const val DESIRED_MTU = 512

        private const val ADDRESS_TYPE_UNKNOWN_API35 = 0

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BRUCE_SERVICE_UUID: UUID = UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91")
        private val BRUCE_CHAR_UUID: UUID = UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        private val BG = 0xFF070A0F.toInt()
        private val CARD_BG = 0xFF141B25.toInt()
        private val CARD_INNER = 0xFF0F141D.toInt()
        private val STROKE = 0xFF283545.toInt()
        private val ACCENT = 0xFF43C59E.toInt()
        private val BLUE = 0xFF00E5FF.toInt()
        private val MUTED = 0xFF9EADBD.toInt()
        private val DANGER = 0xFFE53935.toInt()
        private val TEXT_SOFT = 0xFFB4C2D1.toInt()

        private val STANDARD_SERVICES: Map<Int, String> = mapOf(
            0x1800 to "Generic Access (GAP)",
            0x1801 to "Generic Attribute (GATT)",
            0x1802 to "Immediate Alert",
            0x1803 to "Link Loss",
            0x1804 to "Tx Power",
            0x1805 to "Current Time",
            0x1808 to "Glucose",
            0x1809 to "Health Thermometer",
            0x180A to "Device Information",
            0x180D to "Heart Rate",
            0x180E to "Phone Alert Status",
            0x180F to "Battery",
            0x1810 to "Blood Pressure",
            0x1812 to "Human Interface Device (HID)",
            0x1813 to "Scan Parameters",
            0x1814 to "Running Speed and Cadence",
            0x1816 to "Cycling Speed and Cadence",
            0x181A to "Environmental Sensing",
            0x181B to "Body Composition",
            0x181C to "User Data",
            0x181D to "Weight Scale",
            0x181E to "Bond Management",
            0x1822 to "Pulse Oximeter",
            0x1826 to "Fitness Machine",
            0x183A to "Insulin Delivery",
            0xFE36 to "Google TV / Android TV Remote",
            0xFE59 to "Nordic Secure DFU",
            0xFE9F to "Google Fast Pair",
            0xFD6F to "Exposure Notification"
        )

        private val STANDARD_CHARACTERISTICS: Map<Int, String> = mapOf(
            0x2A00 to "Device Name",
            0x2A01 to "Appearance",
            0x2A04 to "Peripheral Preferred Connection Parameters",
            0x2A05 to "Service Changed",
            0x2A19 to "Battery Level",
            0x2A24 to "Model Number String",
            0x2A25 to "Serial Number String",
            0x2A26 to "Firmware Revision String",
            0x2A27 to "Hardware Revision String",
            0x2A28 to "Software Revision String",
            0x2A29 to "Manufacturer Name String",
            0x2A2B to "Current Time",
            0x2A37 to "Heart Rate Measurement",
            0x2A38 to "Body Sensor Location",
            0x2A39 to "Heart Rate Control Point",
            0x2A4A to "HID Information",
            0x2A4B to "Report Map",
            0x2A4C to "HID Control Point",
            0x2A4D to "Report",
            0x2A4E to "Protocol Mode",
            0x2A50 to "PnP ID",
            0x2A6E to "Temperature",
            0x2A6F to "Humidity",
            0x2A73 to "Pressure",
            0x2A9D to "Weight Measurement",
            0x2AC5 to "Analog Output"
        )

        private val STANDARD_DESCRIPTORS: Map<Int, String> = mapOf(
            0x2900 to "Characteristic Extended Properties",
            0x2901 to "Characteristic User Description",
            0x2902 to "Client Characteristic Configuration (CCCD)",
            0x2903 to "Server Characteristic Configuration",
            0x2904 to "Characteristic Presentation Format",
            0x2905 to "Characteristic Aggregate Format",
            0x2906 to "Valid Range"
        )

        private val MANUFACTURER_NAMES: Map<Int, String> = mapOf(
            0x0006 to "Microsoft",
            0x000D to "Texas Instruments",
            0x000F to "Broadcom",
            0x004C to "Apple",
            0x0059 to "Nordic Semiconductor",
            0x0075 to "Samsung",
            0x00E0 to "Google",
            0x0139 to "Bose",
            0x0157 to "Garmin",
            0x02E5 to "Espressif",
            0x038F to "Xiaomi",
            0x0499 to "Ruuvi",
            0x05A7 to "Sonos"
        )

        private val APPEARANCE_CATEGORIES: Map<Int, String> = mapOf(
            0 to "Nieznany",
            1 to "Telefon",
            2 to "Komputer",
            3 to "Zegarek",
            4 to "Zegar",
            5 to "Wyświetlacz",
            6 to "Pilot",
            7 to "Okulary",
            8 to "Tag / lokalizator",
            9 to "Breloczek",
            10 to "Odtwarzacz mediów",
            11 to "Skaner kodów",
            12 to "Termometr",
            13 to "Czujnik pulsu",
            14 to "Ciśnienie krwi",
            15 to "HID (klawiatura/mysz)",
            16 to "Glukometr",
            17 to "Czujnik biegania",
            18 to "Czujnik rowerowy",
            19 to "Urządzenie sterujące",
            20 to "Urządzenie sieciowe",
            21 to "Sensor",
            22 to "Oprawa świetlna",
            23 to "Wentylator",
            24 to "HVAC",
            25 to "Klimatyzacja",
            26 to "Nawilżacz",
            27 to "Ogrzewanie",
            28 to "Kontrola dostępu",
            29 to "Urządzenie zmotoryzowane",
            30 to "Urządzenie zasilające",
            31 to "Sensor ogólny",
            32 to "Audio Sink",
            33 to "Audio Source",
            34 to "Pojazd",
            35 to "Urządzenie AGD",
            36 to "Wearable Audio",
            37 to "Statek powietrzny",
            38 to "Sprzęt AV",
            39 to "Sprzęt wyświetlający",
            40 to "Aparat słuchowy",
            41 to "Konsola do gier",
            42 to "Oznakowanie cyfrowe"
        )
    }

    // -----------------------------------------------------------------------------------------
    // Pola
    // -----------------------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null

    private lateinit var mainScroll: ScrollView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var filterInput: EditText
    private lateinit var tvScanStatus: TextView
    private lateinit var tvDeviceCount: TextView
    private lateinit var containerDevices: LinearLayout
    private lateinit var onlyNamedSwitch: MaterialSwitch
    private lateinit var onlyConnectableSwitch: MaterialSwitch
    private lateinit var tvGattStatus: TextView
    private lateinit var tvGattTarget: TextView
    private lateinit var tvMtu: TextView
    private lateinit var tvGattLog: TextView
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var containerServices: LinearLayout
    private lateinit var gattSection: LinearLayout

    private val deviceMap = LinkedHashMap<String, DeviceEntry>()
    private var scanning = false
    private var lastScanError: String? = null
    private var onlyNamed = false
    private var onlyConnectable = false
    private var minRssiFilter: Int? = null
    private var sortMode = SortMode.RSSI

    private var activeGatt: BluetoothGatt? = null
    private var connectedAddress: String? = null
    private var connectedName: String? = null
    private var gattConnected = false
    private var gattBusy = false
    private var gattMtu = 0
    private var servicesDiscovered = false
    private var discoveryRequested = false
    private var pendingWriteInfo: String? = null
    private var pendingCccd: CccdMode? = null
    private val subscribedChars = mutableSetOf<String>()
    private val lastValues = LinkedHashMap<String, LastValue>()
    private val charValueLabels = mutableMapOf<String, TextView>()
    private val gattLog: ArrayDeque<GattLogEntry> = ArrayDeque()

    private var pendingAction = PendingAction.NONE
    private var pendingConnectEntry: DeviceEntry? = null
    private var refreshScheduled = false

    // -----------------------------------------------------------------------------------------
    // Launchery Activity Result
    // -----------------------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.isNotEmpty() && grants.values.all { it }
        val action = pendingAction
        val entry = pendingConnectEntry
        pendingAction = PendingAction.NONE
        pendingConnectEntry = null
        if (!granted) {
            lastScanError = "odmowa uprawnień BLE/lokalizacji"
            updateScanUi()
            setScanStatus("❌ Odmówiono uprawnień – skanowanie i GATT nie będą działać")
            toast("Wymagane uprawnienia Bluetooth/lokalizacji zostały odrzucone", long = true)
            return@registerForActivityResult
        }
        when (action) {
            PendingAction.SCAN -> startScanInternal()
            PendingAction.CONNECT -> entry?.let { connectDevice(it) }
            PendingAction.NONE -> Unit
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeReportToUri(uri)
    }

    // -----------------------------------------------------------------------------------------
    // Callbacki skanowania
    // -----------------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
            scheduleRefresh()
        }

        override fun onScanFailed(errorCode: Int) {
            postToUi {
                scanning = false
                lastScanError = scanFailureMessage(errorCode)
                handler.removeCallbacks(refreshRunnable)
                refreshScheduled = false
                updateScanUi()
                appendGattLog("❌ Skanowanie nie powiodło się: ${scanFailureMessage(errorCode)}")
                toast("Błąd skanowania BLE: ${scanFailureMessage(errorCode)}", long = true)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Callbacki GATT (własny, niezależny klient – nie używamy BruceBleManager)
    // -----------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            postToUi {
                if (activeGatt !== gatt) {
                    closeGattQuietly(gatt)
                    return@postToUi
                }
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                        gattConnected = true
                        handler.removeCallbacks(connectionTimeoutRunnable)
                        appendGattLog("🔗 Połączono z ${connectedName ?: gatt.device.address}")
                        updateGattStatusUi()
                        requestMtuAndDiscover(gatt)
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        activeGatt = null
                        handleGattLoss(gatt, "🔌 Rozłączono: ${connectedName ?: gatt.device.address} (status ${gattStatusName(status)})")
                    }

                    else -> {
                        activeGatt = null
                        handleGattLoss(gatt, "❌ Błąd połączenia GATT: ${gattStatusName(status)}")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            postToUi {
                if (activeGatt !== gatt) return@postToUi
                gattBusy = false
                handler.removeCallbacks(gattBusyTimeoutRunnable)
                updateGattStatusUi()
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesDiscovered = true
                    val count = try {
                        gatt.services.size
                    } catch (_: SecurityException) {
                        0
                    }
                    appendGattLog("✅ Odkryto $count usług GATT")
                    renderGattTree(gatt)
                } else {
                    appendGattLog("❌ Błąd odkrywania usług GATT: ${gattStatusName(status)}")
                    tvGattStatus.text = "Status: błąd odkrywania usług (${gattStatusName(status)})"
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            postToUi {
                if (activeGatt !== gatt) return@postToUi
                gattMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 0
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    appendGattLog("⚙ Wynegocjowane MTU: $mtu B (payload ${(mtu - 3).coerceAtLeast(20)} B)")
                } else {
                    appendGattLog("⚠ Negocjacja MTU nieudana: ${gattStatusName(status)}")
                }
                updateGattStatusUi()
                startServiceDiscovery(gatt)
            }
        }

        // API < 33 – starszy wariant odczytu (charakterystyka przechowuje wartość)
        @Deprecated("Wywoływane na Androidzie < 13 (API 33)")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value ?: byteArrayOf()
            postToUi {
                if (activeGatt === gatt) handleReadResult(characteristic, value, status)
            }
        }

        // API 33+ – nowy wariant z przekazaną wartością
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            postToUi {
                if (activeGatt === gatt) handleReadResult(characteristic, value, status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            postToUi {
                if (activeGatt === gatt) handleWriteResult(characteristic, status)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            postToUi {
                if (activeGatt === gatt) handleDescriptorWriteResult(descriptor, status)
            }
        }

        // API < 33 – stary wariant notyfikacji
        @Deprecated("Wywoływane na Androidzie < 13 (API 33)")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: byteArrayOf()
            postToUi {
                if (activeGatt === gatt) handleNotification(characteristic, value)
            }
        }

        // API 33+ – nowy wariant notyfikacji
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            postToUi {
                if (activeGatt === gatt) handleNotification(characteristic, value)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Runnable'y pomocnicze
    // -----------------------------------------------------------------------------------------

    private val refreshRunnable = Runnable {
        refreshScheduled = false
        if (!isFinishing && !isDestroyed) {
            renderDeviceList()
            updateScanUi()
        }
    }

    private val connectionTimeoutRunnable = Runnable {
        if (!gattConnected && activeGatt != null) {
            appendGattLog("⚠ Przekroczono czas łączenia GATT (${CONNECT_TIMEOUT_MS / 1000} s)")
            val lost = activeGatt
            activeGatt = null
            handleGattLoss(lost, "❌ Timeout połączenia GATT")
            tvGattStatus.text = "Status: timeout połączenia"
        }
    }

    private val mtuFallbackRunnable = Runnable {
        val gatt = activeGatt
        if (gatt != null && !discoveryRequested) {
            appendGattLog("ℹ Brak odpowiedzi na requestMtu – przechodzę do discoverServices()")
            startServiceDiscovery(gatt)
        }
    }

    private val gattBusyTimeoutRunnable = Runnable {
        if (gattBusy) {
            gattBusy = false
            appendGattLog("⚠ Przekroczono czas operacji GATT – blokada zwolniona")
            updateGattStatusUi()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Cykl życia
    // -----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        window.statusBarColor = BG
        window.navigationBarColor = BG
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        buildScreen()
        // Android 15+ rysuje edge-to-edge - bez tego nagłówek wchodzi pod pasek statusu.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, 0)
            windowInsets
        }
        updateScanUi()
        updateGattStatusUi()
        if (bluetoothAdapter == null) {
            setScanStatus("❌ To urządzenie nie ma adaptera Bluetooth")
        }
    }

    override fun onStop() {
        // Skanowanie nie startuje automatycznie – zatrzymujemy je przy zejściu z ekranu.
        stopScan(quiet = true)
        handler.removeCallbacks(refreshRunnable)
        refreshScheduled = false
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        try {
            stopScan(quiet = true)
        } catch (_: Exception) {
            // brak uprawnień w trakcie niszczenia aktywności – ignorujemy
        }
        releaseGatt()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------------------------
    // Budowa ekranu (programowo, bez XML)
    // -----------------------------------------------------------------------------------------

    private fun buildScreen() {
        mainScroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(120))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainScroll.addView(root)
        setContentView(mainScroll)

        // Nagłówek
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("BLE API EXPLORER", 10f, ACCENT, true))
        titleStack.addView(text("Skaner & GATT Explorer", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "?"
            textSize = 18f
            minWidth = 0
            setTextColor(ACCENT)
            setOnClickListener { showInfoDialog() }
        }, LinearLayout.LayoutParams(dp(52), dp(46)).apply { marginEnd = dp(6) })

        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 24f
            minWidth = 0
            setTextColor(ACCENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(46)))
        root.addView(header)

        root.addView(text(
            "Podgląd surowych ramek advertising, agregacja RSSI oraz pełny klient GATT " +
                "(usługi, charakterystyki, deskryptory, MTU, zapis/odczyt/CCCD).",
            13f, MUTED
        ).apply { setPadding(0, dp(6), 0, dp(12)) })

        // Karta skanera
        val scanCard = card()
        val scanLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanLayout.addView(text("Skaner BLE (advertising)", 16f, Color.WHITE, true))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnScan = MaterialButton(this).apply {
            text = "▶ START"
            setBackgroundColor(ACCENT)
            setTextColor(Color.BLACK)
            setOnClickListener {
                if (scanning) stopScan() else startScan()
            }
        }
        btnClear = MaterialButton(this).apply {
            text = "🧹 WYCZYŚĆ"
            setBackgroundColor(STROKE)
            setTextColor(Color.WHITE)
            setOnClickListener { clearDevices() }
        }
        actionRow.addView(btnScan, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        actionRow.addView(btnClear, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        scanLayout.addView(actionRow, verticalParams(dp(8)))

        tvScanStatus = text("Status: bezczynny", 12f, MUTED)
        tvScanStatus.setPadding(0, dp(8), 0, dp(4))
        scanLayout.addView(tvScanStatus)

        scanLayout.addView(text("Filtr tekstowy (nazwa lub MAC)", 11f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        filterInput = EditText(this).apply {
            hint = "np. Bruce, TV, AA:BB..."
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            setSingleLine(true)
            background = roundedBackground(CARD_INNER, 10, STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        filterInput.doAfterTextChanged { renderDeviceList() }
        scanLayout.addView(filterInput, verticalParams(dp(2)))

        onlyNamedSwitch = MaterialSwitch(this).apply {
            text = "Tylko urządzenia z nazwą"
            textSize = 12f
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                onlyNamed = checked
                renderDeviceList()
            }
        }
        onlyConnectableSwitch = MaterialSwitch(this).apply {
            text = "Tylko connectable"
            textSize = 12f
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                onlyConnectable = checked
                renderDeviceList()
            }
        }
        scanLayout.addView(onlyNamedSwitch, verticalParams(dp(4)))
        scanLayout.addView(onlyConnectableSwitch)

        scanLayout.addView(text("Minimalny RSSI", 11f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        val rssiOptions = listOf<Int?>(null, -85, -75, -65)
        val rssiGroup = MaterialButtonToggleGroup(this).apply { isSingleSelection = true }
        val rssiIds = IntArray(rssiOptions.size)
        rssiOptions.forEachIndexed { index, value ->
            val button = MaterialButton(this).apply {
                id = View.generateViewId()
                text = value?.let { "≥$it" } ?: "wsz."
                textSize = 10f
                isAllCaps = false
                isSingleLine = true
                minWidth = 0
                setBackgroundColor(CARD_INNER)
                setTextColor(Color.WHITE)
            }
            rssiGroup.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            rssiIds[index] = button.id
        }
        rssiGroup.check(rssiIds[0])
        rssiGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val index = rssiIds.indexOf(checkedId)
                if (index >= 0) {
                    minRssiFilter = rssiOptions[index]
                    renderDeviceList()
                }
            }
        }
        scanLayout.addView(rssiGroup, verticalParams(dp(2)))

        scanLayout.addView(text("Sortowanie", 11f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        val sortGroup = MaterialButtonToggleGroup(this).apply { isSingleSelection = true }
        val sortRssi = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "RSSI (najsilniejsze)"
            textSize = 10f
            isAllCaps = false
            setBackgroundColor(CARD_INNER)
            setTextColor(Color.WHITE)
        }
        val sortName = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Nazwa (A-Z)"
            textSize = 10f
            isAllCaps = false
            setBackgroundColor(CARD_INNER)
            setTextColor(Color.WHITE)
        }
        sortGroup.addView(sortRssi, LinearLayout.LayoutParams(0, -2, 1f))
        sortGroup.addView(sortName, LinearLayout.LayoutParams(0, -2, 1f))
        sortGroup.check(sortRssi.id)
        sortGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                sortMode = if (checkedId == sortName.id) SortMode.NAME else SortMode.RSSI
                renderDeviceList()
            }
        }
        scanLayout.addView(sortGroup)

        scanCard.addView(scanLayout)
        root.addView(scanCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Nagłówek listy urządzeń
        val devicesHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        devicesHeader.addView(text("Wykryte urządzenia", 16f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        tvDeviceCount = text("0", 12f, BLUE, true)
        devicesHeader.addView(tvDeviceCount)
        root.addView(devicesHeader, verticalParams(dp(4)))
        root.addView(text(
            "Kliknij kartę urządzenia, aby zobaczyć pełny rozkład reklamy i otworzyć GATT.",
            11f, MUTED
        ).apply { setPadding(0, dp(2), 0, dp(8)) })

        containerDevices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(containerDevices)

        // Sekcja GATT
        gattSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gattSection, verticalParams(dp(18)))
        gattSection.addView(text("GATT Explorer", 16f, Color.WHITE, true).apply {
            setPadding(dp(2), 0, dp(2), dp(8))
        })

        val gattCard = card()
        val gattLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvGattStatus = text("Status: brak połączenia", 14f, ACCENT, true)
        gattLayout.addView(tvGattStatus)
        tvGattTarget = text("Cel: —", 12f, MUTED)
        tvGattTarget.setPadding(0, dp(4), 0, 0)
        gattLayout.addView(tvGattTarget)
        tvMtu = text("MTU: —", 11f, BLUE)
        tvMtu.setPadding(0, dp(2), 0, dp(8))
        gattLayout.addView(tvMtu)

        val gattActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnDisconnect = MaterialButton(this).apply {
            text = "⏏ ROZŁĄCZ"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(STROKE)
            setTextColor(Color.WHITE)
            isEnabled = false
            setOnClickListener {
                if (activeGatt == null) {
                    toast("Brak aktywnego połączenia GATT")
                } else {
                    appendGattLog("🔌 Rozłączanie zainicjowane przez użytkownika")
                    releaseGatt()
                    tvGattStatus.text = "Status: rozłączono"
                }
            }
        }
        gattActions.addView(btnDisconnect, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        gattActions.addView(MaterialButton(this).apply {
            text = "💾 EKSPORT JSON"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(ACCENT)
            setTextColor(Color.BLACK)
            setOnClickListener { exportReport() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        gattLayout.addView(gattActions)
        gattCard.addView(gattLayout)
        gattSection.addView(gattCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        gattSection.addView(text("Usługi / charakterystyki / deskryptory", 12f, MUTED, true).apply {
            setPadding(dp(4), 0, dp(4), dp(6))
        })
        containerServices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gattSection.addView(containerServices)
        addGattPlaceholder("Brak połączenia. Kliknij kartę urządzenia i wybierz „⚡ GATT (POŁĄCZ)”.")

        gattSection.addView(text("Log powiadomień i operacji GATT", 12f, MUTED, true).apply {
            setPadding(dp(4), dp(14), dp(4), dp(6))
        })
        val logCard = card()
        tvGattLog = text("Brak zdarzeń.\n", 10f, BLUE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logCard.addView(tvGattLog)
        gattSection.addView(logCard, LinearLayout.LayoutParams(-1, -2))
    }

    // -----------------------------------------------------------------------------------------
    // Uprawnienia
    // -----------------------------------------------------------------------------------------

    private fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return missing
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions(action: PendingAction, entry: DeviceEntry? = null): Boolean {
        val missing = missingPermissions()
        if (missing.isEmpty()) return true
        pendingAction = action
        pendingConnectEntry = entry
        try {
            permissionLauncher.launch(missing.toTypedArray())
        } catch (_: Exception) {
            toast("Nie można poprosić o uprawnienia BLE", long = true)
        }
        return false
    }

    // -----------------------------------------------------------------------------------------
    // Skanowanie BLE
    // -----------------------------------------------------------------------------------------

    private fun startScan() {
        if (scanning) return
        if (!ensurePermissions(PendingAction.SCAN)) {
            setScanStatus("Status: oczekiwanie na uprawnienia BLE...")
            return
        }
        startScanInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        if (scanning) return
        if (!ensureBluetoothReady()) return
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            lastScanError = "BluetoothLeScanner niedostępny"
            updateScanUi()
            toast("Ten telefon nie udostępnia skanera BLE", long = true)
            return
        }
        val settings = try {
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        } catch (e: IllegalArgumentException) {
            lastScanError = "błędne ScanSettings: ${e.message}"
            updateScanUi()
            return
        }
        try {
            // Lista filtrów jest opcjonalna – filtrowanie tekstowe robimy w pamięci,
            // żeby nie restartować skanowania przy każdej zmianie filtra.
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            scanning = true
            lastScanError = null
            updateScanUi()
            setScanStatus("Status: ciągłe skanowanie (LOW_LATENCY) • urządzeń: ${deviceMap.size}")
            appendGattLog("▶ Skanowanie BLE uruchomione (SCAN_MODE_LOW_LATENCY, reportDelay=0)")
        } catch (e: SecurityException) {
            scanning = false
            lastScanError = "brak uprawnień BLUETOOTH_SCAN"
            updateScanUi()
            setScanStatus("❌ SecurityException – brak uprawnień do skanowania BLE")
        } catch (e: Exception) {
            scanning = false
            lastScanError = "nie można uruchomić skanera: ${e.message}"
            updateScanUi()
            setScanStatus("❌ Nie można uruchomić skanera: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(quiet: Boolean = false) {
        handler.removeCallbacks(refreshRunnable)
        refreshScheduled = false
        if (!scanning) {
            if (!quiet) updateScanUi()
            return
        }
        scanning = false
        lastScanError = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // uprawnienia odebrane w tle
        } catch (_: IllegalStateException) {
            // skaner już zatrzymany
        }
        updateScanUi()
        setScanStatus(
            if (quiet) "Status: skanowanie zatrzymane (ekran w tle) • urządzeń: ${deviceMap.size}"
            else "Status: skanowanie zatrzymane • urządzeń: ${deviceMap.size}"
        )
    }

    private fun scanFailureMessage(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
            "skanowanie już trwa (SCAN_FAILED_ALREADY_STARTED)"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "brak rejestracji aplikacji w skanerze (SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
            "wewnętrzny błąd stosu BLE (SCAN_FAILED_INTERNAL_ERROR)"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
            "funkcja nieobsługiwana przez kontroler (SCAN_FAILED_FEATURE_UNSUPPORTED)"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
            "brak zasobów sprzętowych (SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES)"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
            "zbyt częste skanowanie – odczekaj chwilę (SCAN_FAILED_SCANNING_TOO_FREQUENTLY)"
        else -> "nieznany błąd skanowania (kod $code)"
    }

    private fun ensureBluetoothReady(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setScanStatus("❌ Brak adaptera Bluetooth w urządzeniu")
            toast("Brak adaptera Bluetooth", long = true)
            return false
        }
        if (!hasConnectPermission()) {
            setScanStatus("❌ Brak uprawnienia BLUETOOTH_CONNECT")
            return false
        }
        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
        if (!enabled) {
            setScanStatus("❌ Bluetooth jest wyłączony – włącz go, aby skanować")
            toast("Bluetooth jest wyłączony", long = true)
            requestEnableBluetooth()
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun requestEnableBluetooth() {
        if (!hasConnectPermission()) return
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
            // brak activity systemowego / brak uprawnień – użytkownik włączy ręcznie
        }
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    // -----------------------------------------------------------------------------------------
    // Agregacja wyników skanowania
    // -----------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val address = try {
            device.address
        } catch (_: SecurityException) {
            return
        }
        val entry = deviceMap.getOrPut(address) { DeviceEntry(address) }
        entry.device = device
        entry.advCount += 1
        val now = System.currentTimeMillis()
        if (entry.firstSeen == 0L) entry.firstSeen = now
        entry.lastSeen = now

        val rssi = result.rssi
        entry.rssiLast = rssi
        if (entry.advCount == 1) {
            entry.rssiMin = rssi
            entry.rssiMax = rssi
        } else {
            if (rssi < entry.rssiMin) entry.rssiMin = rssi
            if (rssi > entry.rssiMax) entry.rssiMax = rssi
        }
        entry.rssiSum += rssi.toLong()
        entry.rssiHistory.addLast(rssi)
        while (entry.rssiHistory.size > RSSI_HISTORY_LIMIT) {
            entry.rssiHistory.removeFirst()
        }

        val record = result.scanRecord
        val parsed = parseAdvertisement(record?.bytes)
        entry.ad = parsed
        val deviceName = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        val adName = parsed.localName
        entry.name = deviceName?.takeIf { it.isNotBlank() } ?: adName ?: entry.name
        // connectable: ScanResult.isConnectable() jest dostępne od API 26 (== minSdk).
        // Na Androidzie 12+ dodatkowo wymagane jest BLUETOOTH_CONNECT do odczytu nazwy.
        entry.connectable = result.isConnectable
        entry.addressType = describeAddressType(device)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            entry.deviceType = describeDeviceType(device)
        }
        scheduleRefresh()
    }

    private fun clearDevices() {
        deviceMap.clear()
        renderDeviceList()
        updateScanUi()
        setScanStatus("Status: wyczyszczono listę • urządzeń: 0")
        toast("Wyczyszczono listę urządzeń")
    }

    // -----------------------------------------------------------------------------------------
    // Parser surowych danych advertising (AD structures)
    // -----------------------------------------------------------------------------------------

    private fun parseAdvertisement(bytes: ByteArray?): AdvParse {
        if (bytes == null || bytes.isEmpty()) return AdvParse.EMPTY

        val records = mutableListOf<AdvRecord>()
        val serviceUuids = mutableListOf<String>()
        val serviceData = mutableListOf<Pair<String, String>>()
        val manufacturerData = mutableListOf<Pair<Int, String>>()
        val unknown = mutableListOf<String>()

        var flags: Int? = null
        var flagsDecoded = ""
        var localName: String? = null
        var localNameComplete: Boolean? = null
        var txPower: Int? = null
        var appearance: Int? = null
        var appearanceLabel: String? = null

        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            index += 1
            val length = bytes[offset].toInt() and 0xFF
            if (length == 0) break // padding kończący ramkę
            if (offset + 1 + length > bytes.size) {
                unknown.add(
                    String.format(
                        Locale.US,
                        "AD#%d: obcięta struktura (len=%d, pozostało=%d bajtów)",
                        index, length, bytes.size - offset - 1
                    )
                )
                break
            }
            val type = bytes[offset + 1].toInt() and 0xFF
            val value = bytes.copyOfRange(offset + 2, offset + 1 + length)
            val valueHex = toHex(value)
            val typeName = adTypeName(type)
            var detail = ""

            when (type) {
                0x01 -> { // Flags
                    if (value.isNotEmpty()) {
                        flags = value[0].toInt() and 0xFF
                        flagsDecoded = decodeFlags(flags)
                        detail = flagsDecoded
                    }
                }

                0x02, 0x03 -> { // 16-bit service UUIDs (incomplete / complete)
                    val uuids = value.toUuid16List()
                    serviceUuids.addAll(uuids)
                    detail = uuids.joinToString(", ")
                }

                0x04, 0x05 -> { // 32-bit service UUIDs
                    val uuids = value.toUuid32List()
                    serviceUuids.addAll(uuids)
                    detail = uuids.joinToString(", ")
                }

                0x06, 0x07 -> { // 128-bit service UUIDs
                    val uuids = value.toUuid128List()
                    serviceUuids.addAll(uuids)
                    detail = uuids.joinToString(", ")
                }

                0x08, 0x09 -> { // Shortened / Complete Local Name
                    val name = decodeName(value)
                    if (name.isNotBlank()) {
                        localName = name
                        localNameComplete = type == 0x09
                    }
                    detail = "\"$name\" (${if (type == 0x09) "pełna" else "skrócona"})"
                }

                0x0A -> { // TX Power Level (int8)
                    if (value.isNotEmpty()) {
                        txPower = value[0].toInt()
                        detail = "$txPower dBm"
                    }
                }

                0x16 -> { // Service Data 16-bit
                    if (value.size >= 2) {
                        val uuid = uuidString16(readUInt16Le(value, 0))
                        val payload = toHex(value.copyOfRange(2, value.size))
                        serviceData.add(uuid to payload)
                        detail = "$uuid → $payload"
                    } else if (value.isNotEmpty()) {
                        detail = "zbyt krótkie dane usługi: $valueHex"
                    }
                }

                0x20 -> { // Service Data 32-bit
                    if (value.size >= 4) {
                        val uuid = uuidString32(readUInt32Le(value, 0))
                        val payload = toHex(value.copyOfRange(4, value.size))
                        serviceData.add(uuid to payload)
                        detail = "$uuid → $payload"
                    } else if (value.isNotEmpty()) {
                        detail = "zbyt krótkie dane usługi: $valueHex"
                    }
                }

                0x21 -> { // Service Data 128-bit
                    if (value.size >= 16) {
                        val uuid = uuidString128Le(value, 0)
                        val payload = toHex(value.copyOfRange(16, value.size))
                        serviceData.add(uuid to payload)
                        detail = "$uuid → $payload"
                    } else if (value.isNotEmpty()) {
                        detail = "zbyt krótkie dane usługi: $valueHex"
                    }
                }

                0x19 -> { // Appearance
                    if (value.size >= 2) {
                        val app = readUInt16Le(value, 0)
                        appearance = app
                        appearanceLabel = appearanceName(app)
                        detail = String.format(Locale.US, "0x%04X – %s", app, appearanceLabel)
                    }
                }

                0xFF -> { // Manufacturer Specific Data
                    if (value.size >= 2) {
                        val companyId = readUInt16Le(value, 0)
                        val payload = toHex(value.copyOfRange(2, value.size))
                        manufacturerData.add(companyId to payload)
                        val company = manufacturerName(companyId)
                        detail = String.format(
                            Locale.US, "CID 0x%04X (%s) → %s",
                            companyId, company ?: "nieznany", payload
                        )
                    } else if (value.isNotEmpty()) {
                        detail = "zbyt krótkie dane producenta: $valueHex"
                    }
                }

                0x14, 0x15, 0x1F -> { // Service Solicitation
                    val uuids = when (type) {
                        0x14 -> value.toUuid16List()
                        0x15 -> value.toUuid128List()
                        else -> value.toUuid32List()
                    }
                    detail = uuids.joinToString(", ")
                }

                0x2C -> {
                    detail = valueHex
                }
            }

            if (typeName.startsWith("Nieznany")) {
                unknown.add(String.format(Locale.US, "0x%02X: %s", type, valueHex))
            }
            records.add(AdvRecord(type, typeName, valueHex, detail))
            offset += 1 + length
        }

        return AdvParse(
            flags = flags,
            flagsDecoded = flagsDecoded,
            localName = localName,
            localNameComplete = localNameComplete,
            txPower = txPower,
            appearance = appearance,
            appearanceName = appearanceLabel,
            serviceUuids = serviceUuids.distinct(),
            serviceData = serviceData,
            manufacturerData = manufacturerData,
            unknownRecords = unknown,
            rawHex = toHex(bytes),
            records = records
        )
    }

    private fun adTypeName(type: Int): String = when (type) {
        0x01 -> "Flags"
        0x02 -> "16-bit Service UUIDs (niekompletna)"
        0x03 -> "16-bit Service UUIDs (pełna)"
        0x04 -> "32-bit Service UUIDs (niekompletna)"
        0x05 -> "32-bit Service UUIDs (pełna)"
        0x06 -> "128-bit Service UUIDs (niekompletna)"
        0x07 -> "128-bit Service UUIDs (pełna)"
        0x08 -> "Skrócona nazwa lokalna"
        0x09 -> "Pełna nazwa lokalna"
        0x0A -> "Poziom TX Power"
        0x0D -> "Klasa urządzenia"
        0x0E -> "Identyfikator urządzenia"
        0x0F -> "Flagi połączenia"
        0x10 -> "Identyfikator zestawu"
        0x12 -> "Zewnętrzne flagi funkcji"
        0x14 -> "16-bit Service Solicitation"
        0x15 -> "128-bit Service Solicitation"
        0x16 -> "Service Data 16-bit"
        0x17 -> "Publiczny adres docelowy"
        0x18 -> "Losowy adres docelowy"
        0x19 -> "Appearance"
        0x1A -> "Rola LE"
        0x1B -> "128-bit Service Solicitation (wewn.)"
        0x1C -> "Identyfikator zestawu 128-bit"
        0x1F -> "32-bit Service Solicitation"
        0x20 -> "Service Data 32-bit"
        0x21 -> "Service Data 128-bit"
        0x24 -> "URI"
        0x27 -> "Flagi LE"
        0x28 -> "Adres LE urządzenia"
        0x29 -> "Secure Connections Confirmation"
        0x2A -> "Secure Connections Random"
        0x2B -> "UUID klasy urządzenia"
        0x2C -> "Kod Broadcast"
        0x2D -> "Informacja 3D"
        0xFF -> "Manufacturer Specific Data"
        else -> String.format(Locale.US, "Nieznany typ 0x%02X", type)
    }

    private fun decodeFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and 0x01 != 0) parts.add("LE Limited Discoverable")
        if (flags and 0x02 != 0) parts.add("LE General Discoverable")
        if (flags and 0x04 != 0) parts.add("BR/EDR niedostępny")
        if (flags and 0x08 != 0) parts.add("LE + BR/EDR (kontroler)")
        if (flags and 0x10 != 0) parts.add("LE + BR/EDR (host)")
        return if (parts.isEmpty()) "brak ustawionych bitów" else parts.joinToString(", ")
    }

    private fun decodeName(value: ByteArray): String {
        val decoded = String(value, Charsets.UTF_8)
        return decoded.filter { it.code in 32..126 || it.code >= 160 }.trim()
    }

    private fun ByteArray.toUuid16List(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i + 2 <= size) {
            result.add(uuidString16(readUInt16Le(this, i)))
            i += 2
        }
        return result
    }

    private fun ByteArray.toUuid32List(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i + 4 <= size) {
            result.add(uuidString32(readUInt32Le(this, i)))
            i += 4
        }
        return result
    }

    private fun ByteArray.toUuid128List(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i + 16 <= size) {
            result.add(uuidString128Le(this, i))
            i += 16
        }
        return result
    }

    private fun readUInt16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32Le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun uuidString16(short: Int): String =
        String.format(Locale.US, "0000%04x%s", short and 0xFFFF, BASE_UUID_SUFFIX)

    private fun uuidString32(value: Long): String =
        String.format(Locale.US, "%08x%s", value and 0xFFFFFFFFL, BASE_UUID_SUFFIX)

    private fun uuidString128Le(data: ByteArray, offset: Int): String {
        val builder = StringBuilder(32)
        for (i in 15 downTo 0) {
            builder.append(String.format(Locale.US, "%02x", data[offset + i].toInt() and 0xFF))
        }
        val hex = builder.toString()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun manufacturerName(companyId: Int): String? = MANUFACTURER_NAMES[companyId]

    private fun appearanceName(value: Int): String {
        val category = value shr 6
        val subCategory = value and 0x3F
        val categoryName = APPEARANCE_CATEGORIES[category] ?: "Kategoria $category"
        return if (subCategory == 0) categoryName else "$categoryName (podtyp $subCategory)"
    }

    // -----------------------------------------------------------------------------------------
    // Lista urządzeń
    // -----------------------------------------------------------------------------------------

    private fun renderDeviceList() {
        if (!::containerDevices.isInitialized) return
        val visible = collectVisibleDevices()
        tvDeviceCount.text = if (visible.size == deviceMap.size) {
            "${deviceMap.size}"
        } else {
            "${visible.size}/${deviceMap.size}"
        }
        containerDevices.removeAllViews()
        if (visible.isEmpty()) {
            containerDevices.addView(text(
                if (deviceMap.isEmpty())
                    "Brak urządzeń. Kliknij „▶ START”, aby rozpocząć ciągłe skanowanie BLE."
                else
                    "Żadne urządzenie nie spełnia aktualnych filtrów.",
                13f, MUTED
            ).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
            return
        }
        visible.take(MAX_RENDERED_DEVICES).forEach { entry ->
            containerDevices.addView(
                buildDeviceCard(entry),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }
        if (visible.size > MAX_RENDERED_DEVICES) {
            containerDevices.addView(text(
                "…oraz ${visible.size - MAX_RENDERED_DEVICES} kolejnych (zawęź filtr, aby zobaczyć więcej)",
                11f, MUTED
            ).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) })
        }
    }

    private fun collectVisibleDevices(): List<DeviceEntry> {
        val query = if (::filterInput.isInitialized) {
            filterInput.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        } else {
            ""
        }
        val minRssi = minRssiFilter
        val filtered = deviceMap.values.filter { entry ->
            if (onlyNamed && !entry.hasName()) return@filter false
            if (onlyConnectable && entry.connectable != true) return@filter false
            if (minRssi != null && entry.rssiLast < minRssi) return@filter false
            if (query.isNotEmpty()) {
                val haystack = "${entry.name.orEmpty()} ${entry.address} ${entry.ad.localName.orEmpty()}"
                    .lowercase(Locale.US)
                if (!haystack.contains(query)) return@filter false
            }
            true
        }
        return when (sortMode) {
            SortMode.RSSI -> filtered.sortedWith(
                compareByDescending<DeviceEntry> { it.rssiLast }.thenBy { it.address }
            )
            SortMode.NAME -> filtered.sortedWith(
                compareBy<DeviceEntry> { it.name?.lowercase(Locale.US) ?: "\uFFFF" }
                    .thenByDescending { it.rssiLast }
            )
        }
    }

    private fun buildDeviceCard(entry: DeviceEntry): MaterialCardView {
        val card = card()
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            text(entry.displayName(), 15f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        titleRow.addView(text("${entry.rssiLast} dBm", 14f, rssiColor(entry.rssiLast), true))
        stack.addView(titleRow)

        stack.addView(text(
            "MAC ${entry.address} • adres: ${entry.addressType} • connectable: ${connectableLabel(entry)}",
            11f, MUTED
        ).apply { setPadding(0, dp(2), 0, 0) })

        stack.addView(text(
            "RSSI śr. ${entry.rssiAvg} • min ${entry.rssiMin} • max ${entry.rssiMax} • reklam: ${entry.advCount}",
            11f, BLUE
        ).apply { setPadding(0, dp(2), 0, 0) })

        val sparkline = RssiSparklineView(this)
        sparkline.setHistory(entry.rssiHistory.toList())
        stack.addView(sparkline, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(4)
            bottomMargin = dp(2)
        })

        val ad = entry.ad
        val manufacturerHint = ad.manufacturerData.firstOrNull()?.let { (id, _) ->
            manufacturerName(id) ?: String.format(Locale.US, "CID 0x%04X", id)
        } ?: "brak"
        val bruceHint = if (isBruceDevice(ad)) " • ⚡ Bruce BLE API" else ""
        stack.addView(text(
            "UUID usług: ${ad.serviceUuids.size} • producent: $manufacturerHint" +
                " • TX: ${ad.txPower?.let { "$it dBm" } ?: "—"}$bruceHint",
            11f, MUTED
        ).apply { setPadding(0, dp(2), 0, 0) })

        card.addView(stack)
        card.setOnClickListener { showDetailDialog(entry) }
        return card
    }

    private fun connectableLabel(entry: DeviceEntry): String = when (entry.connectable) {
        true -> "TAK"
        false -> "NIE"
        null -> "?"
    }

    private fun rssiColor(rssi: Int): Int = when {
        rssi >= -60 -> ACCENT
        rssi >= -80 -> BLUE
        else -> 0xFFEF5350.toInt()
    }

    private fun isBruceDevice(ad: AdvParse): Boolean =
        ad.serviceUuids.any { it.equals(BRUCE_SERVICE_UUID.toString(), ignoreCase = true) }

    // -----------------------------------------------------------------------------------------
    // Sparkline RSSI (własny View)
    // -----------------------------------------------------------------------------------------

    private inner class RssiSparklineView(context: Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3343C59E
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = STROKE
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLUE
            style = Paint.Style.FILL
        }
        private var history: List<Int> = emptyList()

        fun setHistory(values: List<Int>) {
            history = values
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val pad = dp(3).toFloat()
            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
            canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)
            val data = history
            if (data.size < 2) return
            val minValue = (data.minOrNull() ?: 0) - 3
            val maxValue = (data.maxOrNull() ?: 0) + 3
            val range = (maxValue - minValue).coerceAtLeast(1)
            val stepX = (w - 2f * pad) / (data.size - 1)
            val linePath = Path()
            val fillPath = Path()
            data.forEachIndexed { index, rssi ->
                val x = pad + index * stepX
                val y = pad + (h - 2f * pad) * (1f - (rssi - minValue).toFloat() / range)
                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h - pad)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            val lastX = pad + (data.size - 1) * stepX
            fillPath.lineTo(lastX, h - pad)
            fillPath.close()
            val lastY = pad + (h - 2f * pad) *
                (1f - (data.last() - minValue).toFloat() / range)
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
            canvas.drawCircle(lastX, lastY, dp(3).toFloat(), dotPaint)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Dialog szczegółów urządzenia
    // -----------------------------------------------------------------------------------------

    private fun showDetailDialog(entry: DeviceEntry) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content.addView(text(entry.displayName(), 17f, Color.WHITE, true))
        content.addView(text(
            "MAC ${entry.address} • adres: ${entry.addressType} • typ: ${entry.deviceType}",
            11f, BLUE
        ).apply { setPadding(0, dp(2), 0, dp(6)) })

        val sparkline = RssiSparklineView(this)
        sparkline.setHistory(entry.rssiHistory.toList())
        content.addView(sparkline, LinearLayout.LayoutParams(-1, dp(56)).apply {
            bottomMargin = dp(6)
        })

        content.addView(detailLine(
            "RSSI",
            "aktualny ${entry.rssiLast} dBm • min ${entry.rssiMin} • max ${entry.rssiMax} • " +
                "średni ${entry.rssiAvg} dBm • próbek ${entry.rssiHistory.size}"
        ))
        content.addView(detailLine(
            "Advertising",
            "reklam: ${entry.advCount} • pierwsza: ${formatDateTime(entry.firstSeen)} • " +
                "ostatnia: ${formatDateTime(entry.lastSeen)} • connectable: ${connectableLabel(entry)}"
        ))
        content.addView(detailLine(
            "Typ urządzenia",
            "${entry.deviceType} • typ adresu BLE: ${entry.addressType}"
        ))

        val ad = entry.ad
        content.addView(sectionTitle("Reklama BLE — struktury AD"))
        content.addView(detailLine(
            "Flags",
            ad.flags?.let { String.format(Locale.US, "0x%02X – %s", it, ad.flagsDecoded) } ?: "brak"
        ))
        content.addView(detailLine(
            "Nazwa lokalna",
            ad.localName?.let { "\"$it\" (${if (ad.localNameComplete == true) "pełna" else "skrócona"})" }
                ?: "brak"
        ))
        content.addView(detailLine("TX Power", ad.txPower?.let { "$it dBm" } ?: "brak"))
        content.addView(detailLine(
            "Appearance",
            ad.appearance?.let {
                String.format(Locale.US, "0x%04X – %s", it, appearanceName(it))
            } ?: "brak"
        ))
        content.addView(detailLine(
            "Service UUIDs (${ad.serviceUuids.size})",
            if (ad.serviceUuids.isEmpty()) "brak" else ad.serviceUuids.joinToString("\n") { uuid ->
                val name = serviceNameForString(uuid)
                if (name != null) "$uuid\n    → $name" else uuid
            }
        ))
        content.addView(detailLine(
            "Service Data",
            if (ad.serviceData.isEmpty()) "brak" else ad.serviceData.joinToString("\n") { (uuid, hex) ->
                "$uuid → $hex"
            }
        ))
        content.addView(detailLine(
            "Manufacturer Data",
            if (ad.manufacturerData.isEmpty()) "brak"
            else ad.manufacturerData.joinToString("\n") { (id, hex) ->
                String.format(
                    Locale.US, "0x%04X (%s) → %s",
                    id, manufacturerName(id) ?: "nieznany", hex
                )
            }
        ))
        if (ad.unknownRecords.isNotEmpty()) {
            content.addView(detailLine("Inne / nieznane AD", ad.unknownRecords.joinToString("\n")))
        }
        content.addView(sectionTitle("Odczyty AD (typ → dane)"))
        content.addView(text(
            if (ad.records.isEmpty()) "brak"
            else ad.records.joinToString("\n") { record ->
                String.format(
                    Locale.US, "0x%02X %s: %s",
                    record.type, record.typeName,
                    record.detail.ifBlank { record.valueHex }
                )
            },
            10f, MUTED
        ).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        content.addView(sectionTitle("Surowe bajty reklamy"))
        content.addView(text(ad.rawHex.ifBlank { "brak" }, 9f, BLUE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .create()
        val btnGatt = gattActionButton("⚡ GATT (POŁĄCZ)", STROKE, Color.WHITE) {
            dialog.dismiss()
            connectDevice(entry)
        }
        val btnExport = gattActionButton("💾 EKSPORT JSON", ACCENT, Color.BLACK) {
            dialog.dismiss()
            exportReport()
        }
        buttons.addView(btnGatt, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        buttons.addView(btnExport, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        content.addView(buttons)
        dialog.show()
    }

    private fun detailLine(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        row.addView(text(label, 10f, ACCENT, true))
        row.addView(text(value.ifBlank { "—" }, 11f, TEXT_SOFT))
        return row
    }

    private fun sectionTitle(title: String): TextView =
        text(title, 12f, Color.WHITE, true).apply { setPadding(0, dp(14), 0, dp(2)) }

    // -----------------------------------------------------------------------------------------
    // GATT – łączenie i obsługa stanu
    // -----------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connectDevice(entry: DeviceEntry) {
        if (!ensurePermissions(PendingAction.CONNECT, entry)) {
            setScanStatus("Status: oczekiwanie na uprawnienia do połączenia BLE...")
            return
        }
        if (!ensureBluetoothReady()) return
        // Połączenie GATT stabilniej działa przy zatrzymanym skanowaniu.
        stopScan(quiet = true)
        releaseGatt()
        val adapter = bluetoothAdapter ?: return
        val device = entry.device ?: try {
            adapter.getRemoteDevice(entry.address)
        } catch (e: IllegalArgumentException) {
            toast("Nieprawidłowy adres MAC: ${entry.address}", long = true)
            return
        }
        connectedAddress = entry.address
        connectedName = entry.displayName()
        gattConnected = false
        servicesDiscovered = false
        discoveryRequested = false
        gattMtu = 0
        updateGattStatusUi()
        tvGattStatus.text = "Status: łączenie z ${entry.displayName()}..."
        appendGattLog("🔗 Łączenie z ${entry.displayName()} (${entry.address})")
        try {
            activeGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            activeGatt = null
            tvGattStatus.text = "Status: brak uprawnień BLUETOOTH_CONNECT"
            appendGattLog("❌ SecurityException przy connectGatt")
            return
        } catch (e: Exception) {
            activeGatt = null
            tvGattStatus.text = "Status: błąd connectGatt: ${e.message}"
            appendGattLog("❌ Błąd connectGatt: ${e.message}")
            return
        }
        if (activeGatt == null) {
            tvGattStatus.text = "Status: nie udało się utworzyć połączenia GATT"
            return
        }
        updateGattStatusUi()
        handler.removeCallbacks(connectionTimeoutRunnable)
        handler.postDelayed(connectionTimeoutRunnable, CONNECT_TIMEOUT_MS)
        gattSection.post { mainScroll.smoothScrollTo(0, gattSection.top) }
    }

    @SuppressLint("MissingPermission")
    private fun requestMtuAndDiscover(gatt: BluetoothGatt) {
        discoveryRequested = false
        val requested = try {
            gatt.requestMtu(DESIRED_MTU)
        } catch (_: SecurityException) {
            false
        }
        if (requested) {
            appendGattLog("⚙ Żądanie MTU $DESIRED_MTU B")
            handler.removeCallbacks(mtuFallbackRunnable)
            handler.postDelayed(mtuFallbackRunnable, MTU_FALLBACK_MS)
        } else {
            appendGattLog("ℹ requestMtu() odrzucony – odkrywam usługi z domyślnym MTU")
            startServiceDiscovery(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (discoveryRequested) return
        discoveryRequested = true
        handler.removeCallbacks(mtuFallbackRunnable)
        val started = try {
            gatt.discoverServices()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            discoveryRequested = false
            appendGattLog("❌ discoverServices() odrzucone przez stos BLE")
            tvGattStatus.text = "Status: nie udało się odkryć usług GATT"
        }
    }

    private fun handleGattLoss(gatt: BluetoothGatt?, reason: String) {
        closeGattQuietly(gatt)
        gattConnected = false
        gattBusy = false
        servicesDiscovered = false
        discoveryRequested = false
        gattMtu = 0
        pendingWriteInfo = null
        pendingCccd = null
        subscribedChars.clear()
        charValueLabels.clear()
        handler.removeCallbacks(connectionTimeoutRunnable)
        handler.removeCallbacks(mtuFallbackRunnable)
        handler.removeCallbacks(gattBusyTimeoutRunnable)
        appendGattLog(reason)
        resetGattUi()
    }

    private fun resetGattUi() {
        if (!::tvGattStatus.isInitialized) return
        tvGattStatus.text = "Status: brak połączenia"
        tvGattTarget.text = "Cel: —"
        tvMtu.text = "MTU: —"
        if (::btnDisconnect.isInitialized) btnDisconnect.isEnabled = false
        if (::containerServices.isInitialized) {
            containerServices.removeAllViews()
            addGattPlaceholder("Brak połączenia. Kliknij kartę urządzenia i wybierz „⚡ GATT (POŁĄCZ)”.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseGatt() {
        val gatt = activeGatt
        activeGatt = null
        closeGattQuietly(gatt)
        handleGattLoss(null, "🔌 Połączenie GATT zamknięte")
    }

    @SuppressLint("MissingPermission")
    private fun closeGattQuietly(gatt: BluetoothGatt?) {
        if (gatt == null) return
        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
            // brak uprawnień – i tak zamykamy
        }
        try {
            gatt.close()
        } catch (_: SecurityException) {
            // ignorujemy
        }
    }

    private fun updateGattStatusUi() {
        if (!::tvGattStatus.isInitialized) return
        val gatt = activeGatt
        tvGattStatus.text = when {
            gatt == null -> "Status: brak połączenia"
            gattConnected && servicesDiscovered -> {
                val services = try {
                    gatt.services.size
                } catch (_: SecurityException) {
                    0
                }
                "Status: połączono • usług: $services • subskrypcji: ${subscribedChars.size}"
            }
            gattConnected -> "Status: połączono • odkrywanie usług..."
            else -> "Status: łączenie..."
        }
        tvGattTarget.text = "Cel: ${connectedName ?: "—"} (${connectedAddress ?: "—"})"
        tvMtu.text = if (gattMtu > 0) {
            "MTU: $gattMtu B • payload ${(gattMtu - 3).coerceAtLeast(20)} B"
        } else {
            "MTU: —"
        }
        if (::btnDisconnect.isInitialized) btnDisconnect.isEnabled = gatt != null
    }

    // -----------------------------------------------------------------------------------------
    // GATT – drzewo usług / charakterystyk / deskryptorów
    // -----------------------------------------------------------------------------------------

    private fun addGattPlaceholder(message: String) {
        containerServices.addView(text(message, 12f, MUTED).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
    }

    @SuppressLint("MissingPermission")
    private fun renderGattTree(gatt: BluetoothGatt) {
        if (!::containerServices.isInitialized) return
        containerServices.removeAllViews()
        charValueLabels.clear()
        val services = try {
            gatt.services
        } catch (_: SecurityException) {
            emptyList<BluetoothGattService>()
        }
        if (services.isEmpty()) {
            addGattPlaceholder("Brak usług GATT do wyświetlenia.")
            return
        }
        services.forEach { service ->
            containerServices.addView(
                buildServiceCard(service),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            )
        }
        updateGattStatusUi()
    }

    private fun buildServiceCard(service: BluetoothGattService): MaterialCardView {
        val card = card()
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(text(humanServiceName(service.uuid), 14f, ACCENT, true))
        stack.addView(text("Service UUID: ${service.uuid}", 10f, BLUE).apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        service.characteristics.forEach { characteristic ->
            stack.addView(
                buildCharacteristicCard(characteristic),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            )
        }
        card.addView(stack)
        return card
    }

    @SuppressLint("MissingPermission")
    private fun buildCharacteristicCard(characteristic: BluetoothGattCharacteristic): MaterialCardView {
        val card = innerCard()
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(text(humanCharName(characteristic.uuid), 12f, Color.WHITE, true))
        stack.addView(text("UUID: ${characteristic.uuid}", 9f, BLUE).apply {
            setPadding(0, dp(2), 0, dp(2))
        })
        stack.addView(text("Właściwości: ${describeProperties(characteristic.properties)}", 10f, MUTED))
        stack.addView(text("Uprawnienia: ${describePermissions(characteristic.permissions)}", 10f, MUTED))

        val descriptors = try {
            characteristic.descriptors
        } catch (_: SecurityException) {
            emptyList<BluetoothGattDescriptor>()
        }
        if (descriptors.isNotEmpty()) {
            stack.addView(text(
                "Deskryptory:\n" + descriptors.joinToString("\n") {
                    "• ${humanDescriptorName(it.uuid)} [${it.uuid}]"
                },
                9f, MUTED
            ).apply { setPadding(0, dp(3), 0, 0) })
        }

        val key = characteristic.uuid.toString()
        val valueLabel = text(
            lastValues[key]?.let { "Ostatnia wartość: ${it.hex}" } ?: "Ostatnia wartość: —",
            10f, TEXT_SOFT
        ).apply { setPadding(0, dp(4), 0, dp(4)) }
        charValueLabels[key] = valueLabel
        stack.addView(valueLabel)

        val canRead = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        val canWrite = characteristic.properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val canNotify = characteristic.properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (canRead) {
            actions.addView(
                gattActionButton("📖 CZYTAJ", STROKE, Color.WHITE) {
                    requestCharacteristicRead(characteristic)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) }
            )
        }
        if (canWrite) {
            actions.addView(
                gattActionButton("✍️ ZAPISZ", ACCENT, Color.BLACK) {
                    showWriteDialog(characteristic)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = if (canRead) dp(3) else 0
                    marginEnd = if (canNotify) dp(3) else 0
                }
            )
        }
        if (canNotify) {
            val subscribed = subscribedChars.contains(key)
            actions.addView(
                gattActionButton(
                    if (subscribed) "🔕 ANULUJ" else "🔔 SUBSKRYBUJ",
                    BLUE, Color.BLACK
                ) {
                    setSubscribed(characteristic, !subscribed)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = if (canRead || canWrite) dp(3) else 0
                }
            )
        }
        if (actions.childCount > 0) {
            stack.addView(actions, verticalParams(dp(2)))
        }
        card.addView(stack)
        return card
    }

    // -----------------------------------------------------------------------------------------
    // GATT – operacje
    // -----------------------------------------------------------------------------------------

    private fun beginGattOperation(label: String): Boolean {
        if (activeGatt == null) {
            toast("Brak połączenia GATT")
            return false
        }
        if (!servicesDiscovered) {
            toast("Usługi GATT nie zostały jeszcze odkryte")
            return false
        }
        if (gattBusy) {
            toast("Inna operacja GATT jest w toku – poczekaj")
            return false
        }
        gattBusy = true
        handler.removeCallbacks(gattBusyTimeoutRunnable)
        handler.postDelayed(gattBusyTimeoutRunnable, GATT_OP_TIMEOUT_MS)
        if (::tvGattStatus.isInitialized) {
            tvGattStatus.text = "Status: operacja GATT – $label..."
        }
        return true
    }

    private fun endGattOperation() {
        gattBusy = false
        handler.removeCallbacks(gattBusyTimeoutRunnable)
        updateGattStatusUi()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestCharacteristicRead(characteristic: BluetoothGattCharacteristic) {
        val gatt = activeGatt ?: return
        if (!beginGattOperation("odczyt ${shortUuid(characteristic.uuid)}")) return
        // Uwaga: API 33 nie dodaje wariantu readCharacteristic() zwracającego int –
        // nowy styl zwracania kodów dotyczy wyłącznie writeCharacteristic()/writeDescriptor().
        val started = try {
            gatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        }
        if (started) {
            appendGattLog("📥 Żądanie odczytu ${shortUuid(characteristic.uuid)}")
        } else {
            endGattOperation()
            appendGattLog("❌ Stos BLE odrzucił odczyt ${shortUuid(characteristic.uuid)}")
        }
    }

    private fun handleReadResult(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        endGattOperation()
        val key = characteristic.uuid.toString()
        if (status == BluetoothGatt.GATT_SUCCESS) {
            lastValues[key] = LastValue(toHex(value), printableText(value), "read", System.currentTimeMillis())
            updateValueLabel(key, value, "ODCZYT")
            appendGattLog(
                "📥 Odczyt ${shortUuid(characteristic.uuid)} " +
                    "(${value.size} B): ${formatValueInline(value)}"
            )
        } else {
            appendGattLog(
                "❌ Błąd odczytu ${shortUuid(characteristic.uuid)}: ${gattStatusName(status)}"
            )
        }
    }

    private fun handleWriteResult(characteristic: BluetoothGattCharacteristic, status: Int) {
        endGattOperation()
        val info = pendingWriteInfo
        pendingWriteInfo = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            appendGattLog("📤 Zapis zakończony OK: ${info ?: shortUuid(characteristic.uuid)}")
        } else {
            appendGattLog("❌ Zapis ${shortUuid(characteristic.uuid)} nieudany: ${gattStatusName(status)}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDescriptorWriteResult(descriptor: BluetoothGattDescriptor, status: Int) {
        endGattOperation()
        val mode = pendingCccd
        pendingCccd = null
        val characteristicUuid = try {
            descriptor.characteristic.uuid.toString()
        } catch (_: Exception) {
            null
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (characteristicUuid != null && mode != null) {
                if (mode == CccdMode.OFF) {
                    subscribedChars.remove(characteristicUuid)
                } else {
                    subscribedChars.add(characteristicUuid)
                }
            }
            val modeLabel = when (mode) {
                CccdMode.NOTIFY -> "notifications"
                CccdMode.INDICATE -> "indications"
                CccdMode.OFF -> "wyłączone"
                null -> "?"
            }
            appendGattLog(
                "🔔 CCCD zapisany ($modeLabel) dla " +
                    (characteristicUuid?.let { shortUuidOfString(it) } ?: "?")
            )
            activeGatt?.let { if (servicesDiscovered) renderGattTree(it) }
        } else {
            appendGattLog("❌ Zapis CCCD nieudany: ${gattStatusName(status)}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun setSubscribed(characteristic: BluetoothGattCharacteristic, enable: Boolean) {
        val gatt = activeGatt ?: return
        val properties = characteristic.properties
        val supportsNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) {
            toast("Ta charakterystyka nie wspiera notyfikacji")
            return
        }
        val useIndication = !supportsNotify && supportsIndicate
        if (!beginGattOperation(if (enable) "subskrypcja" else "anulowanie subskrypcji")) return
        val localOk = try {
            gatt.setCharacteristicNotification(characteristic, enable)
        } catch (_: SecurityException) {
            false
        }
        if (!localOk) {
            endGattOperation()
            appendGattLog("❌ setCharacteristicNotification() odrzucone")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            val key = characteristic.uuid.toString()
            if (enable) subscribedChars.add(key) else subscribedChars.remove(key)
            endGattOperation()
            appendGattLog(
                "ℹ Brak CCCD – powiadomienia lokalne ${if (enable) "włączone" else "wyłączone"} " +
                    "dla ${shortUuid(characteristic.uuid)}"
            )
            activeGatt?.let { renderGattTree(it) }
            return
        }
        pendingCccd = when {
            !enable -> CccdMode.OFF
            useIndication -> CccdMode.INDICATE
            else -> CccdMode.NOTIFY
        }
        val descriptorValue = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            useIndication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                gatt.writeDescriptor(descriptor, descriptorValue) == BluetoothStatusCodes.SUCCESS
            } catch (_: SecurityException) {
                false
            }
        } else {
            descriptor.value = descriptorValue
            try {
                gatt.writeDescriptor(descriptor)
            } catch (_: SecurityException) {
                false
            }
        }
        if (!started) {
            pendingCccd = null
            endGattOperation()
            appendGattLog("❌ Zapis CCCD odrzucony przez stos BLE")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun performWrite(
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ) {
        val gatt = activeGatt ?: return
        if (!beginGattOperation("zapis ${payload.size} B")) return
        pendingWriteInfo = "${shortUuid(characteristic.uuid)} • ${payload.size} B • ${formatValueInline(payload)}"
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                gatt.writeCharacteristic(characteristic, payload, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } catch (_: SecurityException) {
                false
            }
        } else {
            characteristic.writeType = writeType
            characteristic.value = payload
            try {
                gatt.writeCharacteristic(characteristic)
            } catch (_: SecurityException) {
                false
            }
        }
        if (started) {
            appendGattLog("📤 Wysłano zapis (${payload.size} B): ${toHex(payload)}")
        } else {
            pendingWriteInfo = null
            endGattOperation()
            appendGattLog("❌ Stos BLE odrzucił zapis (${payload.size} B)")
        }
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val key = characteristic.uuid.toString()
        lastValues[key] = LastValue(
            toHex(value), printableText(value), "notify", System.currentTimeMillis()
        )
        updateValueLabel(key, value, "NOTYFIKACJA")
        appendGattLog(
            "🔔 Powiadomienie ${shortUuid(characteristic.uuid)} (${value.size} B): " +
                formatValueInline(value)
        )
    }

    private fun updateValueLabel(key: String, value: ByteArray, source: String) {
        val label = charValueLabels[key] ?: return
        label.text = "$source • ${value.size} B • ${formatValueInline(value)}"
    }

    // -----------------------------------------------------------------------------------------
    // Dialog zapisu charakterystyki
    // -----------------------------------------------------------------------------------------

    private fun showWriteDialog(characteristic: BluetoothGattCharacteristic) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(text("Zapis do ${humanCharName(characteristic.uuid)}", 14f, ACCENT, true))
        root.addView(text(characteristic.uuid.toString(), 10f, MUTED).apply {
            setPadding(0, dp(2), 0, dp(8))
        })

        val input = EditText(this).apply {
            hint = "Tekst lub HEX (np. AA 01 FF)"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            background = roundedBackground(CARD_INNER, 10, STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(input, verticalParams(dp(4)))

        root.addView(text("Format danych", 11f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(2))
        })
        val formatGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbText = RadioButton(this).apply {
            id = View.generateViewId()
            text = "TEKST (UTF-8)"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val rbHex = RadioButton(this).apply {
            id = View.generateViewId()
            text = "HEX (bajty, np. AA BB 01)"
            setTextColor(Color.WHITE)
        }
        formatGroup.addView(rbText)
        formatGroup.addView(rbHex)
        root.addView(formatGroup)

        root.addView(text("Typ zapisu", 11f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(2))
        })
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val canNoResponse = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val rbDefault = RadioButton(this).apply {
            id = View.generateViewId()
            text = "WRITE (z odpowiedzią)"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val rbNoResponse = RadioButton(this).apply {
            id = View.generateViewId()
            text = if (canNoResponse) {
                "WRITE_NO_RESPONSE (bez odpowiedzi)"
            } else {
                "WRITE_NO_RESPONSE (niedostępne dla tej charakterystyki)"
            }
            setTextColor(if (canNoResponse) Color.WHITE else MUTED)
            isEnabled = canNoResponse
        }
        typeGroup.addView(rbDefault)
        typeGroup.addView(rbNoResponse)
        root.addView(typeGroup)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val dialog = AlertDialog.Builder(this).setView(root).create()
        val btnCancel = gattActionButton("ANULUJ", STROKE, Color.WHITE) { dialog.dismiss() }
        val btnSend = gattActionButton("ZAPISZ", ACCENT, Color.BLACK) {
            val raw = input.text?.toString() ?: ""
            val payload = if (rbHex.isChecked) hexToBytes(raw) else raw.toByteArray(Charsets.UTF_8)
            if (payload == null || payload.isEmpty()) {
                toast(
                    if (rbHex.isChecked) "Nieprawidłowy HEX (parzysta liczba cyfr) lub puste dane"
                    else "Wpisz dane do wysłania"
                )
            } else {
                val writeType = if (rbNoResponse.isChecked && canNoResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                dialog.dismiss()
                performWrite(characteristic, payload, writeType)
            }
        }
        buttons.addView(btnCancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        buttons.addView(btnSend, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        root.addView(buttons)
        dialog.show()
    }

    private fun hexToBytes(text: String): ByteArray? {
        val cleaned = text.replace(Regex("[^0-9A-Fa-f]"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    // -----------------------------------------------------------------------------------------
    // Log zdarzeń GATT
    // -----------------------------------------------------------------------------------------

    private fun appendGattLog(message: String) {
        val now = System.currentTimeMillis()
        val entry = GattLogEntry(now, message, formatClock(now))
        gattLog.addLast(entry)
        while (gattLog.size > GATT_LOG_LIMIT) gattLog.removeFirst()
        if (!::tvGattLog.isInitialized) return
        val builder = StringBuilder()
        gattLog.forEach { item ->
            builder.append(item.clock).append("  ").append(item.message).append('\n')
        }
        tvGattLog.text = builder.toString()
    }

    // -----------------------------------------------------------------------------------------
    // Eksport JSON (CreateDocument)
    // -----------------------------------------------------------------------------------------

    private fun exportReport() {
        if (deviceMap.isEmpty() && activeGatt == null) {
            toast("Brak danych do eksportu – najpierw zeskanuj urządzenia", long = true)
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        try {
            createDocumentLauncher.launch("ble_api_report_$stamp.json")
        } catch (e: Exception) {
            toast("Nie można otworzyć okna zapisu: ${e.message}")
        }
    }

    private fun writeReportToUri(uri: Uri) {
        // JSON budujemy na wątku UI (spójna migawka agregacji), zapis pliku idzie na IO.
        val jsonText = try {
            buildReportJson().toString(2)
        } catch (e: Exception) {
            toast("Błąd budowania raportu JSON: ${e.message}", long = true)
            return
        }
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(jsonText.toByteArray(Charsets.UTF_8))
                    } ?: error("nie można otworzyć strumienia")
                    true
                } catch (e: Exception) {
                    false
                }
            }
            toast(
                if (success) "Raport JSON zapisany" else "Nie udało się zapisać raportu JSON",
                long = !success
            )
        }
    }

    private fun buildReportJson(): JSONObject {
        val root = JSONObject()
        root.put("app", "BruceIR BLE API Explorer")
        root.put("generatedAt", formatIso(System.currentTimeMillis()))
        root.put(
            "adapterEnabled",
            try {
                bluetoothAdapter?.isEnabled == true
            } catch (_: SecurityException) {
                false
            }
        )
        root.put("scanning", scanning)
        root.put(
            "filters",
            JSONObject()
                .put("query", if (::filterInput.isInitialized) filterInput.text?.toString().orEmpty() else "")
                .put("onlyNamed", onlyNamed)
                .put("onlyConnectable", onlyConnectable)
                .put("minRssi", minRssiFilter ?: JSONObject.NULL)
                .put("sort", sortMode.name)
        )
        root.put("deviceCount", deviceMap.size)
        val devices = JSONArray()
        deviceMap.values.sortedByDescending { it.rssiLast }.forEach { entry ->
            devices.put(buildDeviceJson(entry))
        }
        root.put("devices", devices)
        val gatt = activeGatt
        if (gatt != null && gattConnected) {
            root.put("gatt", buildGattJson(gatt))
        } else {
            root.put("gatt", JSONObject.NULL)
        }
        return root
    }

    private fun buildDeviceJson(entry: DeviceEntry): JSONObject {
        val obj = JSONObject()
        obj.put("name", entry.name ?: JSONObject.NULL)
        obj.put("localNameFromAd", entry.ad.localName ?: JSONObject.NULL)
        obj.put("address", entry.address)
        obj.put("addressType", entry.addressType)
        obj.put("deviceType", entry.deviceType)
        obj.put("connectable", entry.connectable ?: JSONObject.NULL)
        obj.put("advertisementCount", entry.advCount)
        obj.put("firstSeen", formatIso(entry.firstSeen))
        obj.put("lastSeen", formatIso(entry.lastSeen))

        val rssi = JSONObject()
        rssi.put("last", entry.rssiLast)
        rssi.put("min", entry.rssiMin)
        rssi.put("max", entry.rssiMax)
        rssi.put("avg", entry.rssiAvg)
        rssi.put("history", JSONArray(entry.rssiHistory.toList()))
        obj.put("rssi", rssi)

        val ad = entry.ad
        ad.txPower?.let { obj.put("txPower", it) }
        if (ad.flags != null) {
            obj.put("flags", JSONObject().put("raw", ad.flags).put("decoded", ad.flagsDecoded))
        } else {
            obj.put("flags", JSONObject.NULL)
        }
        if (ad.appearance != null) {
            obj.put(
                "appearance",
                JSONObject().put("raw", ad.appearance).put("name", appearanceName(ad.appearance))
            )
        } else {
            obj.put("appearance", JSONObject.NULL)
        }
        obj.put("serviceUuids", JSONArray(ad.serviceUuids))
        obj.put("serviceData", buildServiceDataJson(ad.serviceData))
        obj.put("manufacturerData", buildManufacturerJson(ad.manufacturerData))
        obj.put("adRecords", buildAdRecordsJson(ad.records))
        obj.put("unknownAdRecords", JSONArray(ad.unknownRecords))
        obj.put("rawAdvertisingHex", ad.rawHex)
        return obj
    }

    private fun buildServiceDataJson(data: List<Pair<String, String>>): JSONArray {
        val array = JSONArray()
        data.forEach { (uuid, hex) ->
            array.put(JSONObject().put("uuid", uuid).put("dataHex", hex))
        }
        return array
    }

    private fun buildManufacturerJson(data: List<Pair<Int, String>>): JSONArray {
        val array = JSONArray()
        data.forEach { (companyId, hex) ->
            val item = JSONObject()
            item.put("companyId", companyId)
            item.put("companyIdHex", String.format(Locale.US, "0x%04X", companyId))
            item.put("companyName", manufacturerName(companyId) ?: JSONObject.NULL)
            item.put("dataHex", hex)
            array.put(item)
        }
        return array
    }

    private fun buildAdRecordsJson(records: List<AdvRecord>): JSONArray {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("type", record.type)
                    .put("typeHex", String.format(Locale.US, "0x%02X", record.type))
                    .put("typeName", record.typeName)
                    .put("valueHex", record.valueHex)
                    .put("detail", record.detail)
            )
        }
        return array
    }

    @SuppressLint("MissingPermission")
    private fun buildGattJson(gatt: BluetoothGatt): JSONObject {
        val obj = JSONObject()
        obj.put("address", connectedAddress ?: gatt.device.address)
        obj.put("name", connectedName ?: JSONObject.NULL)
        obj.put("mtu", gattMtu)
        obj.put(
            "negotiatedPayloadBytes",
            if (gattMtu > 3) gattMtu - 3 else JSONObject.NULL
        )
        val services = try {
            gatt.services
        } catch (_: SecurityException) {
            emptyList<BluetoothGattService>()
        }
        val servicesArray = JSONArray()
        services.forEach { service ->
            val serviceObj = JSONObject()
            serviceObj.put("uuid", service.uuid.toString())
            serviceObj.put("name", humanServiceName(service.uuid))
            val characteristics = JSONArray()
            service.characteristics.forEach { characteristic ->
                val charObj = JSONObject()
                charObj.put("uuid", characteristic.uuid.toString())
                charObj.put("name", humanCharName(characteristic.uuid))
                charObj.put("properties", describeProperties(characteristic.properties))
                charObj.put("permissions", describePermissions(characteristic.permissions))
                val descriptors = try {
                    characteristic.descriptors
                } catch (_: SecurityException) {
                    emptyList<BluetoothGattDescriptor>()
                }
                val descriptorsArray = JSONArray()
                descriptors.forEach { descriptor ->
                    descriptorsArray.put(
                        JSONObject()
                            .put("uuid", descriptor.uuid.toString())
                            .put("name", humanDescriptorName(descriptor.uuid))
                    )
                }
                charObj.put("descriptors", descriptorsArray)
                val lastValue = lastValues[characteristic.uuid.toString()]
                if (lastValue != null) {
                    charObj.put(
                        "lastValue",
                        JSONObject()
                            .put("hex", lastValue.hex)
                            .put("text", lastValue.text)
                            .put("source", lastValue.source)
                            .put("at", formatIso(lastValue.timeMs))
                    )
                }
                characteristics.put(charObj)
            }
            serviceObj.put("characteristics", characteristics)
            servicesArray.put(serviceObj)
        }
        obj.put("services", servicesArray)
        val logArray = JSONArray()
        gattLog.forEach { entry ->
            logArray.put(
                JSONObject()
                    .put("time", formatIso(entry.timeMs))
                    .put("message", entry.message)
            )
        }
        obj.put("eventLog", logArray)
        return obj
    }

    // -----------------------------------------------------------------------------------------
    // Nazwy standardowych UUID / właściwości
    // -----------------------------------------------------------------------------------------

    private fun standardUuid16(uuid: UUID): Int? {
        val text = uuid.toString().lowercase(Locale.US)
        if (!text.endsWith(BASE_UUID_SUFFIX)) return null
        val head = text.substring(0, 8)
        if (!head.startsWith("0000")) return null
        return head.substring(4).toIntOrNull(16)
    }

    private fun shortUuid(uuid: UUID): String {
        val short = standardUuid16(uuid)
        return if (short != null) String.format(Locale.US, "0x%04X", short) else uuid.toString().take(8)
    }

    private fun shortUuidOfString(uuidText: String): String {
        val uuid = try {
            UUID.fromString(uuidText)
        } catch (_: IllegalArgumentException) {
            null
        }
        return uuid?.let { shortUuid(it) } ?: uuidText.take(8)
    }

    private fun serviceNameForString(uuidText: String): String? {
        val uuid = try {
            UUID.fromString(uuidText)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return humanServiceName(uuid)
    }

    private fun humanServiceName(uuid: UUID): String {
        if (uuid == BRUCE_SERVICE_UUID) {
            return "⚡ Bruce BLE API – port szeregowy CLI (IR/RF/Wi-Fi)"
        }
        val short = standardUuid16(uuid)
            ?: return "Usługa producenta / nieznana (${uuid.toString().take(8)}…)"
        return STANDARD_SERVICES[short] ?: String.format(Locale.US, "Usługa 0x%04X", short)
    }

    private fun humanCharName(uuid: UUID): String {
        if (uuid == BRUCE_CHAR_UUID) {
            return "⚡ Bruce serial TX/RX (komendy IR/RF, READ/WRITE/NOTIFY)"
        }
        val short = standardUuid16(uuid)
            ?: return "Charakterystyka producenta (${uuid.toString().take(8)}…)"
        return STANDARD_CHARACTERISTICS[short] ?: String.format(Locale.US, "Charakterystyka 0x%04X", short)
    }

    private fun humanDescriptorName(uuid: UUID): String {
        if (uuid == CCCD_UUID) return "CCCD – Client Characteristic Configuration"
        val short = standardUuid16(uuid)
            ?: return "Deskryptor producenta"
        return STANDARD_DESCRIPTORS[short] ?: String.format(Locale.US, "Deskryptor 0x%04X", short)
    }

    private fun describeProperties(properties: Int): String {
        val parts = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) parts.add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            parts.add("WRITE_NO_RESPONSE")
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) parts.add("SIGNED_WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) parts.add("EXTENDED_PROPS")
        return if (parts.isEmpty()) "brak" else parts.joinToString(", ")
    }

    private fun describePermissions(permissions: Int): String {
        val parts = mutableListOf<String>()
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ != 0) parts.add("READ")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0) {
            parts.add("READ_ENCRYPTED")
        }
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0) {
            parts.add("READ_ENCRYPTED_MITM")
        }
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE != 0) parts.add("WRITE")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0) {
            parts.add("WRITE_ENCRYPTED")
        }
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0) {
            parts.add("WRITE_ENCRYPTED_MITM")
        }
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED != 0) {
            parts.add("WRITE_SIGNED")
        }
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM != 0) {
            parts.add("WRITE_SIGNED_MITM")
        }
        return if (parts.isEmpty()) "brak" else parts.joinToString(", ")
    }

    private fun gattStatusName(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "SUKCES"
        BluetoothGatt.GATT_READ_NOT_PERMITTED -> "odczyt niedozwolony"
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "zapis niedozwolony"
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "wymagana autoryzacja"
        BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION -> "brak autoryzacji"
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "wymagane szyfrowanie"
        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "żądanie nieobsługiwane"
        BluetoothGatt.GATT_INVALID_OFFSET -> "nieprawidłowy offset"
        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "nieprawidłowa długość atrybutu"
        BluetoothGatt.GATT_CONNECTION_CONGESTED -> "połączenie przeciążone"
        BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "timeout połączenia"
        BluetoothGatt.GATT_FAILURE -> "ogólny błąd GATT"
        else -> "kod $status"
    }

    // -----------------------------------------------------------------------------------------
    // Opis typu adresu / urządzenia
    // -----------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun describeAddressType(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT >= 35) {
            // getAddressType() jest dostępne od API 35 – pokazuje realny typ adresu BLE.
            val type = try {
                device.addressType
            } catch (_: SecurityException) {
                ADDRESS_TYPE_UNKNOWN_API35
            }
            return when (type) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "Publiczny"
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> "Losowy (RPA)"
                BluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> "Anonimowy"
                else -> "Nieznany"
            }
        }
        return heuristicAddressType(device)
    }

    private fun heuristicAddressType(device: BluetoothDevice): String {
        val firstOctet = device.address.substringBefore(':').toIntOrNull(16) ?: return "Nieznany"
        return when (firstOctet and 0xC0) {
            0xC0 -> "Losowy statyczny (heurystyka)"
            0x40 -> "Losowy prywatny RPA (heurystyka)"
            else -> "Publiczny / losowy (heurystyka)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun describeDeviceType(device: BluetoothDevice): String = try {
        when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
            else -> "Nieznany"
        }
    } catch (_: SecurityException) {
        "—"
    }

    // -----------------------------------------------------------------------------------------
    // Pomocnicze: formatowanie, UI, wątki
    // -----------------------------------------------------------------------------------------

    private fun postToUi(block: () -> Unit) {
        handler.post {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun updateScanUi() {
        if (!::btnScan.isInitialized) return
        if (scanning) {
            btnScan.text = "⏹ STOP"
            btnScan.setBackgroundColor(DANGER)
            btnScan.setTextColor(Color.WHITE)
        } else {
            btnScan.text = "▶ START"
            btnScan.setBackgroundColor(ACCENT)
            btnScan.setTextColor(Color.BLACK)
        }
        if (!::tvScanStatus.isInitialized) return
        val adapter = bluetoothAdapter
        val bluetoothOn = if (adapter != null && hasConnectPermission()) {
            try {
                adapter.isEnabled
            } catch (_: SecurityException) {
                false
            }
        } else {
            false
        }
        val adapterState = when {
            adapter == null -> "brak adaptera"
            !hasConnectPermission() -> "brak uprawnień"
            bluetoothOn -> "BT włączony"
            else -> "BT wyłączony"
        }
        val permissionsState =
            if (missingPermissions().isEmpty()) "uprawnienia OK" else "brak uprawnień"
        val error = lastScanError
        tvScanStatus.text = when {
            scanning -> "Status: skanowanie aktywne • $adapterState • $permissionsState • " +
                "urządzeń: ${deviceMap.size}"
            error != null -> "Status: ❌ $error"
            else -> "Status: bezczynny • $adapterState • $permissionsState • " +
                "urządzeń: ${deviceMap.size}"
        }
    }

    private fun setScanStatus(message: String) {
        if (::tvScanStatus.isInitialized) tvScanStatus.text = message
    }

    private fun formatValueInline(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(pusto)"
        val truncated = bytes.size > 48
        val hexBytes = if (truncated) bytes.copyOf(48) else bytes
        val hex = toHex(hexBytes) + if (truncated) " …" else ""
        val text = printableText(bytes, 64)
        return "HEX[$hex] TXT[\"$text\"]"
    }

    private fun printableText(bytes: ByteArray, limit: Int = 96): String {
        val builder = StringBuilder()
        val count = minOf(bytes.size, limit)
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xFF
            builder.append(if (value in 32..126) value.toChar() else '.')
        }
        if (bytes.size > limit) builder.append('…')
        return builder.toString()
    }

    private fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return bytes.joinToString(" ") {
            String.format(Locale.US, "%02X", it.toInt() and 0xFF)
        }
    }

    private fun formatClock(timeMs: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeMs))

    private fun formatDateTime(timeMs: Long): String {
        if (timeMs <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
    }

    private fun formatIso(timeMs: Long): String {
        if (timeMs <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timeMs))
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("ℹ BLE API Explorer")
            .setMessage(
                "Ten moduł łączy skaner advertisingu z pełnym klientem GATT.\n\n" +
                    "• SKANOWANIE – ciągłe (SCAN_MODE_LOW_LATENCY, reportDelay=0, " +
                    "CALLBACK_TYPE_ALL_MATCHES). Zbiera nazwę, MAC, typ adresu, connectable, " +
                    "RSSI (min/max/średnia/historia) oraz liczbę reklam.\n\n" +
                    "• PARSER AD – własna analiza scanRecord.bytes: Flags, nazwa lokalna, TX Power, " +
                    "UUID 16/32/128-bit, Service Data, Manufacturer Data, Appearance i nieznane typy.\n\n" +
                    "• GATT – niezależne połączenie (własny BluetoothGattCallback): requestMtu(512), " +
                    "discoverServices(), drzewo usług/charakterystyk/deskryptorów, odczyt (HEX + UTF-8), " +
                    "zapis TEKST/HEX z wyborem WRITE / WRITE_NO_RESPONSE, subskrypcja NOTIFY/INDICATE " +
                    "przez CCCD oraz ograniczony log powiadomień.\n\n" +
                    "• EKSPORT JSON – migawka skanu (wszystkie pola, rozkład AD, historia RSSI) i – jeśli " +
                    "połączono – całe drzewo GATT z ostatnimi odczytami.\n\n" +
                    "Wymagane uprawnienia: Android 12+ BLUETOOTH_SCAN + BLUETOOTH_CONNECT, " +
                    "starsze wersje ACCESS_FINE_LOCATION. Skanowanie startuje wyłącznie z przycisku " +
                    "i zatrzymuje się przy zejściu z ekranu."
            )
            .setPositiveButton("ROZUMIEM", null)
            .show()
    }

    private fun gattActionButton(
        label: String,
        background: Int,
        textColor: Int,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setBackgroundColor(background)
        setTextColor(textColor)
        setOnClickListener { onClick() }
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(CARD_BG)
        strokeColor = STROKE
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun innerCard(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(10).toFloat()
        setCardBackgroundColor(CARD_INNER)
        strokeColor = STROKE
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun roundedBackground(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun verticalParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
