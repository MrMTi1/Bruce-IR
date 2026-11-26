package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dark palette and limits. The module is self-contained: no XML resources, hardcoded labels.
private const val MAX_RSSI_HISTORY = 30
private const val RENDER_THROTTLE_MS = 1_000L
private const val SCAN_TIMEOUT_MS = 12_000L
private const val THROTTLE_NOTICE_INTERVAL_MS = 10_000L
private const val MIN_THROTTLE_BACKOFF_MS = 3_000L
private const val BAND_24_GHZ = "2.4 GHz"
private const val BAND_5_GHZ = "5 GHz"
private const val BAND_6_GHZ = "6 GHz"
private const val BAND_60_GHZ = "60 GHz"
private const val BAND_UNKNOWN = "?"
private const val SECURITY_OPEN = "OPEN"

private val COLOR_BACKGROUND = 0xFF070A0F.toInt()
private val COLOR_CARD = 0xFF141B25.toInt()
private val COLOR_STROKE = 0xFF283545.toInt()
private val COLOR_ACCENT_GREEN = 0xFF43C59E.toInt()
private val COLOR_ACCENT_BLUE = 0xFF00E5FF.toInt()
private val COLOR_MUTED = 0xFF9EADBD.toInt()
private val COLOR_RISK = 0xFFFF7A6B.toInt()
private val COLOR_STOP = 0xFFE5484D.toInt()
private val COLOR_WARN = 0xFFF2C14E.toInt()
private val COLOR_DARK_ON_ACCENT = 0xFF05231C.toInt()
private val COLOR_SPARK_FILL = 0x3343C59E

private enum class ScanFilter { ALL, OPEN, WEP, WPS, BAND_24, BAND_5, BAND_6 }

private enum class SortMode { RSSI, SSID }

private enum class SignalTrend(val arrow: String, val label: String) {
    IMPROVING("↑", "poprawa"),
    STABLE("→", "stabilnie"),
    DEGRADING("↓", "spadek")
}

/** One aggregated BSSID with a bounded RSSI history. */
private class NetworkRecord(
    var ssid: String,
    val bssid: String,
    var frequency: Int,
    var capabilities: String,
    var level: Int,
    var seenCount: Int,
    var firstSeen: Long,
    var lastSeen: Long,
    var lastResult: ScanResult
) {
    val samples: MutableList<Int> = ArrayList(MAX_RSSI_HISTORY)

    fun recordSample(levelDbm: Int) {
        if (samples.size >= MAX_RSSI_HISTORY) samples.removeAt(0)
        samples.add(levelDbm)
    }

    fun sampleCount(): Int = samples.size
    fun averageLevel(): Int = if (samples.isEmpty()) level else samples.sum() / samples.size
    fun minLevel(): Int = samples.minOrNull() ?: level
    fun maxLevel(): Int = samples.maxOrNull() ?: level
}

/** Aggressive/continuous Wi-Fi scanner with per-BSSID history, risk flags, filters and export. */
class AggressiveWifiScanActivity : AppCompatActivity() {

    private val wifiManager: WifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val scanIntervalsMs = intArrayOf(1_000, 2_000, 5_000, 10_000)
    private val networks = LinkedHashMap<String, NetworkRecord>()
    private val filterButtons = LinkedHashMap<ScanFilter, MaterialButton>()

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var occupancyView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var startStopButton: MaterialButton
    private lateinit var scanOnceButton: MaterialButton
    private lateinit var intervalButton: MaterialButton
    private lateinit var sortButton: MaterialButton
    private lateinit var continuousSwitch: MaterialSwitch

    private var continuousScan = false
    private var pendingContinuousStart = false
    private var pendingManualScan = false
    private var permissionRequestedOnce = false
    private var scanPending = false
    private var receiverRegistered = false
    private var suppressSwitchCallbacks = false
    private var intervalIndex = 1
    private var activeFilter = ScanFilter.ALL
    private var sortMode = SortMode.RSSI
    private var searchQuery = ""
    private var scanRequestCount = 0
    private var scanSuccessCount = 0
    private var throttleRejections = 0
    private var lastThrottleNoticeAt = 0L
    private var lastResultsAt = 0L
    private var lastRenderAt = 0L
    private var renderScheduled = false

    private val renderRunnable = Runnable {
        renderScheduled = false
        lastRenderAt = SystemClock.uptimeMillis()
        rebuildList()
    }
    private val nextScanRunnable = Runnable { performScan(manual = false) }
    private val scanTimeoutRunnable = Runnable { onScanTimeout() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (missingPermissions().isEmpty()) {
            setStatus("Przyznano wymagane uprawnienia.")
            if (pendingContinuousStart) {
                pendingContinuousStart = false
                pendingManualScan = false
                startContinuousScan()
            } else if (pendingManualScan) {
                pendingManualScan = false
                performScan(manual = true)
            }
        } else {
            pendingContinuousStart = false
            pendingManualScan = false
            updateScanControls()
            setStatus("Brak wymaganych uprawnień — skanowanie Wi-Fi niemożliwe.")
            Toast.makeText(this, "Brak uprawnień (lokalizacja / NEARBY_WIFI_DEVICES).", Toast.LENGTH_LONG).show()
        }
    }

    private val csvExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) writeSnapshot(uri, asJson = false) }

    private val jsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeSnapshot(uri, asJson = true) }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            val wasPending = scanPending
            scanPending = false
            val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (fresh) {
                scanSuccessCount += 1
                lastResultsAt = System.currentTimeMillis()
            }
            val results = readScanResults()
            mergeResults(results, fresh)
            when {
                fresh -> setStatus("Świeże wyniki: ${results.size} sieci · ${networks.size} BSSID w bazie.")
                wasPending -> setStatus("Odebrano wyniki z cache (bez aktualizacji) — możliwy throttling systemu.")
                else -> Unit
            }
            updateStatsLine()
            if (continuousScan) scheduleNextScan(scanIntervalMs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.NETWORK)) return
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        buildScreen()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, 0)
            windowInsets
        }
        updateScanControls()
        styleFilterButtons()
        updateStatsLine()
        requestRender(force = true)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    scanReceiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
            receiverRegistered = true
        }
        setStatus(wifiStateDescription())
        updateStatsLine()
        if (!permissionRequestedOnce && missingPermissions().isNotEmpty()) {
            permissionRequestedOnce = true
            permissionLauncher.launch(missingPermissions().toTypedArray())
        }
    }

    override fun onStop() {
        continuousScan = false
        scanPending = false
        pendingContinuousStart = false
        pendingManualScan = false
        renderScheduled = false
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.removeCallbacks(nextScanRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(scanReceiver) }
            receiverRegistered = false
        }
        if (::startStopButton.isInitialized) updateScanControls()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI ---

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)

        body.addView(buildHeader())
        body.addLabel(
            "Ciągły, szybki skan Wi-Fi z historią sygnału, oceną ryzyka, filtrami oraz eksportem CSV/JSON.",
            13f, COLOR_MUTED, top = 6, bottom = 12
        )
        body.addView(buildControlsCard(), cardLayoutParams())
        body.addView(buildStatusBlock())
        body.addView(buildExportRow(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        body.addView(buildOccupancyCard(), cardLayoutParams())
        body.addView(buildFilterCard(), cardLayoutParams())
        body.addLabel("WYKRYTE SIECI", 15f, Color.WHITE, true, left = 2, top = 2, right = 2, bottom = 8)
        body.addLabel(
            "Kliknij kartę, aby zobaczyć pełne szczegóły ScanResult i statystyki historii.",
            11f, COLOR_MUTED, left = 2, right = 2, bottom = 8
        )
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listContainer)
        body.addLabel(
            "Uwaga: Android może ograniczać częstotliwość skanów (throttling). Moduł używa wtedy " +
                "ostatnich wyników i pokazuje stosowny komunikat.",
            10f, COLOR_MUTED, left = 2, top = 16, right = 2
        )
    }

    private fun buildHeader(): LinearLayout {
        val header = rowLayout()
        val titles = columnLayout()
        titles.addLabel("AGGRESSIVE WI-FI SCAN", 10f, COLOR_ACCENT_GREEN, true)
        titles.addLabel("Agresywny Skan Wi-Fi", 24f, Color.WHITE, true, top = 3)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val infoButton = outlinedButton("[?]", "Informacje o module agresywnego skanowania Wi-Fi") { showInfoDialog() }
        header.addView(infoButton, LinearLayout.LayoutParams(dp(50), dp(46)).apply { marginEnd = dp(6) })
        val backButton = outlinedButton("←", "Wróć do poprzedniego ekranu") { finish() }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        return header
    }

    private fun buildControlsCard(): MaterialCardView {
        val card = card()
        val column = columnLayout()
        column.addLabel("STEROWANIE SKANEM", 11f, COLOR_ACCENT_GREEN, true)
        startStopButton = filledButton("▶ Start ciągły", "Uruchom lub zatrzymaj ciągły skan Wi-Fi") {
            toggleContinuousScan()
        }
        scanOnceButton = outlinedButton("⟳ Skanuj raz", "Wykonaj pojedynczy skan Wi-Fi") { requestManualScan() }
        val buttons = rowLayout()
        buttons.addView(startStopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        buttons.addView(scanOnceButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        column.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        intervalButton = outlinedButton("Interwał: ${intervalLabel()}", "Zmień interwał skanowania") {
            cycleInterval()
        }
        continuousSwitch = MaterialSwitch(this).apply {
            text = "Tryb ciągły"
            setTextColor(COLOR_MUTED)
            contentDescription = "Włącz lub wyłącz tryb ciągłego skanowania"
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
                if (checked) startContinuousScan() else stopContinuousScan()
            }
        }
        val options = rowLayout()
        options.addView(intervalButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        options.addView(continuousSwitch)
        column.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(column)
        return card
    }

    private fun buildStatusBlock(): LinearLayout {
        val block = columnLayout()
        statusView = text("Gotowy do skanowania.", 13f, 0xFFB4C2D1.toInt()).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        statsView = text("", 11f, COLOR_MUTED).apply { setPadding(dp(2), dp(8), dp(2), dp(12)) }
        block.addView(statusView)
        block.addView(statsView)
        return block
    }

    private fun buildExportRow(): LinearLayout {
        val row = rowLayout(centerVertical = false)
        val csvButton = outlinedButton("Eksport CSV", "Eksportuj zagregowane wyniki do pliku CSV") {
            startExport(asJson = false)
        }
        val jsonButton = outlinedButton("Eksport JSON", "Eksportuj zagregowane wyniki do pliku JSON") {
            startExport(asJson = true)
        }
        row.addView(csvButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        row.addView(jsonButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        return row
    }

    private fun buildOccupancyCard(): MaterialCardView {
        val card = card()
        val column = columnLayout()
        column.addLabel("OBSADA KANAŁÓW", 11f, COLOR_ACCENT_GREEN, true)
        occupancyView = text("Brak danych — uruchom skan.", 11f, COLOR_MUTED).apply {
            setPadding(0, dp(8), 0, 0)
            typeface = Typeface.MONOSPACE
        }
        column.addView(occupancyView)
        card.addView(column)
        return card
    }

    private fun buildFilterCard(): MaterialCardView {
        val card = card()
        val column = columnLayout()
        column.addLabel("FILTRY I WYSZUKIWANIE", 11f, COLOR_ACCENT_GREEN, true)
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
        }
        val filterRow = rowLayout()
        ScanFilter.values().forEach { mode ->
            val button = filterButton(mode)
            filterButtons[mode] = button
            filterRow.addView(button, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        }
        filterScroll.addView(filterRow)
        column.addView(filterScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        sortButton = outlinedButton(sortButtonLabel(), "Zmień sposób sortowania listy sieci") { cycleSort() }
        column.addView(sortButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        searchInput = EditText(this).apply {
            hint = "Szukaj SSID / BSSID..."
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8290A2.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundResource(R.drawable.button_glass_bg)
            contentDescription = "Pole wyszukiwania sieci po SSID lub BSSID"
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                requestRender()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        column.addView(searchInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        card.addView(column)
        return card
    }

    private fun filterButton(mode: ScanFilter): MaterialButton =
        outlinedButton(filterLabel(mode), "Filtr: ${filterLabel(mode)}") {
            activeFilter = if (activeFilter == mode) ScanFilter.ALL else mode
            styleFilterButtons()
            requestRender(force = true)
        }.apply {
            textSize = 11f
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
        }

    // ---------------------------------------------------------- scan engine ---

    private fun toggleContinuousScan() {
        if (continuousScan) stopContinuousScan() else startContinuousScan()
    }

    private fun startContinuousScan() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingContinuousStart = true
            pendingManualScan = false
            permissionLauncher.launch(missing.toTypedArray())
            setStatus("Wymagane uprawnienia — potwierdź, aby rozpocząć skan ciągły.")
            return
        }
        if (!isWifiReady()) {
            updateScanControls()
            return
        }
        continuousScan = true
        updateScanControls()
        setStatus("Skan ciągły uruchomiony (interwał ${intervalLabel()}).")
        performScan(manual = false)
    }

    private fun stopContinuousScan() {
        continuousScan = false
        scanPending = false
        pendingContinuousStart = false
        pendingManualScan = false
        mainHandler.removeCallbacks(nextScanRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        updateScanControls()
        updateStatsLine()
        setStatus("Skan ciągły zatrzymany. ${networks.size} BSSID w bazie.")
    }

    private fun requestManualScan() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingManualScan = true
            pendingContinuousStart = false
            permissionLauncher.launch(missing.toTypedArray())
            setStatus("Wymagane uprawnienia — potwierdź, aby wykonać skan.")
            return
        }
        performScan(manual = true)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun performScan(manual: Boolean) {
        if (!::statusView.isInitialized) return
        if (scanPending) {
            if (manual) setStatus("Skan już trwa — czekam na wyniki…")
            return
        }
        if (!isWifiReady()) {
            if (continuousScan) stopContinuousScan() else updateScanControls()
            return
        }
        scanRequestCount += 1
        updateStatsLine()
        if (manual) setStatus("Wykonuję skan…")
        val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
        if (started) {
            scanPending = true
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        } else {
            // Throttling: reuse cached results instead of hammering startScan().
            scanPending = false
            throttleRejections += 1
            val cached = readScanResults()
            lastResultsAt = System.currentTimeMillis()
            mergeResults(cached, fresh = false)
            notifyThrottle(cached.size)
            updateStatsLine()
            if (continuousScan) scheduleNextScan(throttleBackoffMs())
        }
    }

    private fun scheduleNextScan(delayMs: Long) {
        mainHandler.removeCallbacks(nextScanRunnable)
        if (!continuousScan) return
        mainHandler.postDelayed(nextScanRunnable, delayMs)
    }

    private fun onScanTimeout() {
        if (!scanPending) return
        scanPending = false
        if (!continuousScan) {
            setStatus("Brak odpowiedzi skanera — spróbuj ponownie.")
            updateStatsLine()
            return
        }
        setStatus("Brak wyników w ${SCAN_TIMEOUT_MS / 1_000} s — ponawiam skan.")
        updateStatsLine()
        scheduleNextScan(scanIntervalMs())
    }

    private fun notifyThrottle(cachedCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastThrottleNoticeAt < THROTTLE_NOTICE_INTERVAL_MS) return
        lastThrottleNoticeAt = now
        setStatus(
            "Android ogranicza częstotliwość skanów (throttling) — używam ostatnich wyników " +
                "($cachedCount sieci). Zmniejsz interwał lub poczekaj chwilę."
        )
    }

    private fun throttleBackoffMs(): Long = maxOf(scanIntervalMs(), MIN_THROTTLE_BACKOFF_MS)
    private fun scanIntervalMs(): Long = scanIntervalsMs[intervalIndex].toLong()
    private fun intervalLabel(): String = "${scanIntervalsMs[intervalIndex] / 1_000} s"

    private fun cycleInterval() {
        intervalIndex = (intervalIndex + 1) % scanIntervalsMs.size
        updateScanControls()
        updateStatsLine()
        if (continuousScan) {
            scheduleNextScan(scanIntervalMs())
            setStatus("Interwał skanowania zmieniony na ${intervalLabel()}.")
        } else {
            setStatus("Interwał skanowania: ${intervalLabel()}.")
        }
    }

    private fun cycleSort() {
        sortMode = if (sortMode == SortMode.RSSI) SortMode.SSID else SortMode.RSSI
        if (::sortButton.isInitialized) sortButton.text = sortButtonLabel()
        requestRender(force = true)
    }

    private fun sortButtonLabel(): String = when (sortMode) {
        SortMode.RSSI -> "Sortowanie: RSSI ↓"
        SortMode.SSID -> "Sortowanie: SSID A-Z"
    }

    private fun updateScanControls() {
        if (!::startStopButton.isInitialized) return
        startStopButton.text = if (continuousScan) "■ Stop" else "▶ Start ciągły"
        val background = if (continuousScan) COLOR_STOP else COLOR_ACCENT_GREEN
        startStopButton.backgroundTintList = ColorStateList.valueOf(background)
        startStopButton.setTextColor(if (continuousScan) Color.WHITE else COLOR_DARK_ON_ACCENT)
        intervalButton.text = "Interwał: ${intervalLabel()}"
        if (::continuousSwitch.isInitialized) {
            suppressSwitchCallbacks = true
            continuousSwitch.isChecked = continuousScan
            suppressSwitchCallbacks = false
        }
    }

    // ------------------------------------------------ permissions / state ---

    private fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        // NEARBY_WIFI_DEVICES is a runtime permission only since Android 13 (API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return missing
    }

    private fun isWifiReady(): Boolean {
        val wifiEnabled = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)
        if (!wifiEnabled) {
            setStatus("Wi-Fi jest wyłączony — włącz Wi-Fi, aby skanować sieci.")
            return false
        }
        if (!isLocationServicesEnabled()) {
            setStatus("Usługi lokalizacji są wyłączone — Android wymaga ich do skanowania Wi-Fi.")
            return false
        }
        return true
    }

    private fun isLocationServicesEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(true)
    }

    private fun wifiStateDescription(): String {
        val wifiLabel = if (runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)) {
            "Wi-Fi: WŁĄCZONE"
        } else {
            "Wi-Fi: WYŁĄCZONE"
        }
        val locationLabel = if (isLocationServicesEnabled()) "lokalizacja: włączona" else "lokalizacja: wyłączona"
        return "$wifiLabel · $locationLabel · interwał: ${intervalLabel()}"
    }

    @SuppressLint("MissingPermission")
    private fun readScanResults(): List<ScanResult> {
        val results = runCatching { wifiManager.scanResults }.getOrNull()
        return results ?: emptyList()
    }

    // -------------------------------------------------- aggregation / list ---

    @Suppress("DEPRECATION")
    private fun mergeResults(results: List<ScanResult>, fresh: Boolean) {
        if (results.isEmpty()) {
            requestRender()
            return
        }
        val now = System.currentTimeMillis()
        results.forEach { result ->
            val bssid = result.BSSID.orEmpty()
            if (bssid.isBlank()) return@forEach
            val ssid = result.SSID.orEmpty()
            val existing = networks[bssid]
            if (existing == null) {
                val record = NetworkRecord(
                    ssid = ssid,
                    bssid = bssid,
                    frequency = result.frequency,
                    capabilities = result.capabilities.orEmpty(),
                    level = result.level,
                    seenCount = 1,
                    firstSeen = now,
                    lastSeen = now,
                    lastResult = result
                )
                record.recordSample(result.level)
                networks[bssid] = record
            } else {
                if (ssid.isNotBlank()) existing.ssid = ssid
                existing.frequency = result.frequency
                existing.capabilities = result.capabilities.orEmpty()
                existing.level = result.level
                existing.lastResult = result
                if (fresh) {
                    existing.seenCount += 1
                    existing.lastSeen = now
                    existing.recordSample(result.level)
                }
            }
        }
        requestRender()
    }

    private fun requestRender(force: Boolean = false) {
        if (!::listContainer.isInitialized) return
        val elapsed = SystemClock.uptimeMillis() - lastRenderAt
        if (force || elapsed >= RENDER_THROTTLE_MS) {
            mainHandler.removeCallbacks(renderRunnable)
            renderRunnable.run()
        } else if (!renderScheduled) {
            renderScheduled = true
            mainHandler.postDelayed(renderRunnable, RENDER_THROTTLE_MS - elapsed)
        }
    }

    private fun rebuildList() {
        if (!::listContainer.isInitialized) return
        val records = filteredRecords()
        listContainer.removeAllViews()
        if (records.isEmpty()) {
            listContainer.addLabel(
                "Brak sieci spełniających kryteria. Uruchom skan lub zmień filtr.",
                13f, COLOR_MUTED, left = 4, top = 12, right = 4, bottom = 12
            )
        } else {
            records.forEach { record -> listContainer.addView(networkCard(record)) }
        }
        updateOccupancy()
        updateStatsLine()
    }

    private fun filteredRecords(): List<NetworkRecord> {
        val query = searchQuery.trim().lowercase(Locale.getDefault())
        val filtered = networks.values.filter { record ->
            val caps = record.capabilities.uppercase(Locale.US)
            val matchesFilter = when (activeFilter) {
                ScanFilter.ALL -> true
                ScanFilter.OPEN -> securityType(record.capabilities) == SECURITY_OPEN
                ScanFilter.WEP -> caps.contains("WEP")
                ScanFilter.WPS -> caps.contains("WPS")
                ScanFilter.BAND_24 -> bandOf(record.frequency) == BAND_24_GHZ
                ScanFilter.BAND_5 -> bandOf(record.frequency) == BAND_5_GHZ
                ScanFilter.BAND_6 -> bandOf(record.frequency) == BAND_6_GHZ
            }
            val matchesSearch = query.isEmpty() ||
                record.ssid.lowercase(Locale.getDefault()).contains(query) ||
                record.bssid.lowercase(Locale.US).contains(query)
            matchesFilter && matchesSearch
        }
        return when (sortMode) {
            SortMode.RSSI -> filtered.sortedByDescending { it.level }
            SortMode.SSID -> filtered.sortedWith(
                compareBy<NetworkRecord>(
                    { if (it.ssid.isBlank()) 1 else 0 },
                    { it.ssid.lowercase(Locale.getDefault()) },
                    { it.bssid }
                )
            )
        }
    }

    private fun networkCard(record: NetworkRecord): MaterialCardView {
        val flags = riskFlags(record)
        val trend = signalTrend(record)
        val percent = WifiSignalUtils.signalPercent(record.level)
        val card = card().apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Szczegóły sieci ${displaySsid(record)} (${record.bssid})"
            setOnClickListener { showDetails(record) }
        }
        val row = rowLayout(centerVertical = false)
        val column = columnLayout()
        column.addLabel(displaySsid(record), 15f, Color.WHITE, true)
        column.addLabel(record.bssid, 11f, COLOR_MUTED, top = 2)
        val signalLine =
            "ch ${WifiSignalUtils.channel(record.frequency)} · ${bandOf(record.frequency)} · " +
                "${record.level} dBm · $percent% · ${trend.arrow} ${trend.label}"
        column.addLabel(signalLine, 12f, signalColor(percent), top = 4)
        column.addLabel(
            "${securityType(record.capabilities)} · widziano ${record.seenCount}× · ${formatClock(record.lastSeen)}",
            11f, COLOR_MUTED, top = 3
        )
        if (flags.isNotEmpty()) column.addLabel("⚠ ${flags.joinToString(" · ")}", 11f, COLOR_RISK, top = 4)
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        val sparkline = SparklineView(this).apply {
            setValues(record.samples.toList())
            contentDescription = "Historia sygnału: ${record.sampleCount()} próbek"
        }
        row.addView(
            sparkline,
            LinearLayout.LayoutParams(dp(92), dp(44)).apply {
                marginStart = dp(10)
                topMargin = dp(2)
            }
        )
        card.addView(row)
        return card
    }

    private fun updateOccupancy() {
        if (!::occupancyView.isInitialized) return
        val counts24 = IntArray(15)
        val counts5 = LinkedHashMap<Int, Int>()
        val counts6 = LinkedHashMap<Int, Int>()
        networks.values.forEach { record ->
            val channel = WifiSignalUtils.channel(record.frequency).toIntOrNull() ?: return@forEach
            when (bandOf(record.frequency)) {
                BAND_24_GHZ -> if (channel in 1..14) counts24[channel] += 1
                BAND_5_GHZ -> counts5[channel] = (counts5[channel] ?: 0) + 1
                BAND_6_GHZ -> counts6[channel] = (counts6[channel] ?: 0) + 1
                else -> Unit
            }
        }
        val line24 = (1..14).filter { counts24[it] > 0 }
            .joinToString("  ") { channel -> "ch$channel:${counts24[channel]}" }
        val line5 = counts5.entries.sortedBy { it.key }
            .joinToString("  ") { (channel, count) -> "ch$channel:$count" }
        val line6 = counts6.entries.sortedBy { it.key }
            .joinToString("  ") { (channel, count) -> "ch$channel:$count" }
        val lines = mutableListOf("2.4 GHz  " + line24.ifEmpty { "brak" }, "5 GHz    " + line5.ifEmpty { "brak" })
        if (line6.isNotEmpty()) lines.add("6 GHz    $line6")
        occupancyView.text = lines.joinToString("\n")
    }

    private fun updateStatsLine() {
        if (!::statsView.isInitialized) return
        val lastResult = if (lastResultsAt > 0L) formatClock(lastResultsAt) else "—"
        val modeLabel = if (continuousScan) "ciągły ${intervalLabel()}" else "ręczny"
        statsView.text =
            "Skany: $scanRequestCount · wyniki: $scanSuccessCount · throttle: $throttleRejections · " +
                "BSSID: ${networks.size} · tryb: $modeLabel · ostatnie: $lastResult"
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun styleFilterButtons() {
        filterButtons.forEach { (mode, button) ->
            val selected = mode == activeFilter
            button.setTextColor(if (selected) COLOR_ACCENT_GREEN else COLOR_MUTED)
            button.setStrokeColor(ColorStateList.valueOf(if (selected) COLOR_ACCENT_GREEN else COLOR_STROKE))
        }
    }

    private fun filterLabel(mode: ScanFilter): String = when (mode) {
        ScanFilter.ALL -> "Wszystkie"
        ScanFilter.OPEN -> "Otwarte"
        ScanFilter.WEP -> "WEP"
        ScanFilter.WPS -> "WPS"
        ScanFilter.BAND_24 -> "2.4G"
        ScanFilter.BAND_5 -> "5G"
        ScanFilter.BAND_6 -> "6G"
    }

    // ------------------------------------------------- classification / risk ---

    private fun displaySsid(record: NetworkRecord): String =
        record.ssid.takeIf { it.isNotBlank() } ?: "SSID ukryty"

    private fun bandOf(frequencyMhz: Int): String = when {
        frequencyMhz in 2_400..2_500 -> BAND_24_GHZ
        frequencyMhz in 4_900..5_924 -> BAND_5_GHZ
        frequencyMhz in 5_925..7_125 -> BAND_6_GHZ
        frequencyMhz in 57_000..71_000 -> BAND_60_GHZ
        else -> BAND_UNKNOWN
    }

    private fun securityType(capabilities: String): String {
        val caps = capabilities.uppercase(Locale.US)
        val enterprise = caps.contains("EAP") || caps.contains("802.1X")
        return when {
            caps.contains("WEP") -> "WEP"
            caps.contains("SAE") || caps.contains("WPA3") -> "WPA3"
            caps.contains("WPA2") -> if (enterprise) "WPA2-Enterprise" else "WPA2"
            caps.contains("WPA") -> "WPA"
            else -> SECURITY_OPEN
        }
    }

    private fun riskFlags(record: NetworkRecord): List<String> {
        val flags = mutableListOf<String>()
        val caps = record.capabilities.uppercase(Locale.US)
        if (securityType(record.capabilities) == SECURITY_OPEN) flags.add("OPEN")
        if (caps.contains("WEP")) flags.add("WEP")
        if (caps.contains("WPS")) flags.add("WPS")
        if (caps.contains("TKIP")) flags.add("TKIP")
        if (record.ssid.isBlank()) flags.add("HIDDEN SSID")
        if (duplicateSsidCount(record.ssid) > 1) flags.add("DUPLIKAT SSID")
        if (caps.contains("802.11B")) flags.add("802.11b")
        return flags
    }

    private fun duplicateSsidCount(ssid: String): Int {
        if (ssid.isBlank()) return 0
        return networks.values.count { it.ssid == ssid }
    }

    private fun signalTrend(record: NetworkRecord): SignalTrend {
        val samples = record.samples
        if (samples.size < 6) return SignalTrend.STABLE
        val window = (samples.size / 3).coerceIn(2, 6)
        val recent = samples.subList(samples.size - window, samples.size).average()
        val older = samples.subList(0, samples.size - window).average()
        val delta = recent - older
        return when {
            delta >= 2.5 -> SignalTrend.IMPROVING
            delta <= -2.5 -> SignalTrend.DEGRADING
            else -> SignalTrend.STABLE
        }
    }

    private fun signalColor(percent: Int): Int = when {
        percent >= 80 -> COLOR_ACCENT_GREEN
        percent >= 60 -> COLOR_ACCENT_BLUE
        percent >= 40 -> COLOR_WARN
        else -> COLOR_RISK
    }

    // ------------------------------------------------------------ dialogs ---

    @Suppress("DEPRECATION")
    private fun showDetails(record: NetworkRecord) {
        val result = record.lastResult
        val percent = WifiSignalUtils.signalPercent(record.level)
        val trend = signalTrend(record)
        val lines = mutableListOf<String>()
        lines.add("SSID: ${displaySsid(record)}")
        lines.add("BSSID: ${record.bssid}")
        lines.add("Pasmo: ${bandOf(record.frequency)} · kanał: ${WifiSignalUtils.channel(record.frequency)}")
        lines.add("Częstotliwość: ${record.frequency} MHz")
        lines.add("RSSI (antena): ${record.level} dBm · $percent% · ${WifiSignalUtils.qualityText(percent)}")
        lines.add("Dystans (szac.): ${WifiSignalUtils.estimatedDistanceMeters(record.level, record.frequency)}")
        lines.add("Bezpieczeństwo: ${securityType(record.capabilities)}")
        lines.add("Capabilities: ${record.capabilities.ifBlank { "brak" }}")
        lines.add("Flagi ryzyka: ${riskFlags(record).ifEmpty { listOf("brak") }.joinToString(" · ")}")
        lines.add("")
        lines.add("HISTORIA RSSI (${record.sampleCount()}/$MAX_RSSI_HISTORY)")
        lines.add("min ${record.minLevel()} / avg ${record.averageLevel()} / max ${record.maxLevel()} dBm")
        lines.add("Trend: ${trend.label} ${trend.arrow}")
        lines.add("Ostatnie próbki: ${record.samples.takeLast(10).joinToString(", ")} dBm")
        lines.add(
            "Widziano: ${record.seenCount}× · pierwszy ${formatDateTime(record.firstSeen)} · " +
                "ostatni ${formatDateTime(record.lastSeen)}"
        )
        lines.add("")
        lines.add("SZCZEGÓŁY ScanResult")
        lines.add("timestamp: ${formatScanTimestamp(result.timestamp)}")
        lines.add("channelWidth: ${channelWidthLabel(result.channelWidth)}")
        lines.add("centerFreq0: ${result.centerFreq0} MHz · centerFreq1: ${result.centerFreq1} MHz")
        lines.add("802.11mc responder: ${result.is80211mcResponder}")
        lines.add("Passpoint: ${result.isPasspointNetwork}")
        val venue = result.venueName?.toString()?.trim().orEmpty()
        if (venue.isNotEmpty()) lines.add("Venue: $venue")
        val operator = result.operatorFriendlyName?.toString()?.trim().orEmpty()
        if (operator.isNotEmpty()) lines.add("Operator: $operator")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lines.add("Standard Wi-Fi: ${wifiStandardLabel(result.wifiStandard)}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lines.add("WifiSsid: ${result.wifiSsid?.toString().orEmpty()}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            lines.add("TWT responder: ${result.isTwtResponder}")
            lines.add("802.11az NTB responder: ${result.is80211azNtbResponder}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            lines.add("Secure HE-LTF: ${result.isSecureHeLtfSupported}")
            lines.add("Ranging frame protection: ${result.isRangingFrameProtectionRequired}")
        }
        AlertDialog.Builder(this)
            .setTitle(displaySsid(record))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("ZAMKNIJ", null)
            .show()
    }

    private fun showInfoDialog() {
        val permissionsHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Wi-Fi włączone oraz uprawnienia: lokalizacja i NEARBY_WIFI_DEVICES."
        } else {
            "Wi-Fi włączone oraz uprawnienie do lokalizacji (wymagane przez Androida do skanów)."
        }
        AlertDialog.Builder(this)
            .setTitle("ℹ Agresywny Skan Wi-Fi")
            .setMessage(
                "Moduł wykonuje serię szybkich skanów Wi-Fi (co 1, 2, 5 lub 10 s), a następnie agreguje " +
                    "wyniki per BSSID i pokazuje je na żywo.\n\n" +
                    "• historia RSSI (do $MAX_RSSI_HISTORY próbek) z min/avg/max i trendem,\n" +
                    "• flagi ryzyka: OPEN, WEP, WPS, TKIP, ukryty SSID, duplikat SSID na wielu BSSID, 802.11b,\n" +
                    "• filtry (wszystkie / otwarte / WEP / WPS / 2.4G / 5G / 6G) oraz wyszukiwanie SSID i BSSID,\n" +
                    "• podsumowanie zajętości kanałów 2.4 / 5 / 6 GHz,\n" +
                    "• pełne szczegóły ScanResult po kliknięciu karty,\n" +
                    "• eksport zagregowanego snapshotu do CSV lub JSON.\n\n" +
                    "Wymagania: $permissionsHint\n\n" +
                    "Android może ograniczać częstotliwość skanów (throttling) — wtedy moduł używa ostatnich " +
                    "wyników i informuje o tym w linii statusu. To narzędzie diagnostyczne: odległości i trend " +
                    "są szacunkami, a nie pomiarem laboratoryjnym."
            )
            .setPositiveButton("ROZUMIEM", null)
            .show()
    }

    private fun wifiStandardLabel(standard: Int): String = when (standard) {
        0 -> "UNKNOWN"
        1 -> "802.11 legacy"
        4 -> "802.11n (Wi-Fi 4)"
        5 -> "802.11ac (Wi-Fi 5)"
        6 -> "802.11ax (Wi-Fi 6)"
        7 -> "802.11ad (WiGig)"
        8 -> "802.11be (Wi-Fi 7)"
        else -> "nieznany ($standard)"
    }

    private fun channelWidthLabel(width: Int): String = when (width) {
        0 -> "20 MHz"
        1 -> "40 MHz"
        2 -> "80 MHz"
        3 -> "160 MHz"
        4 -> "80+80 MHz"
        5 -> "320 MHz"
        else -> "nieznana ($width)"
    }

    // ------------------------------------------------------------- export ---

    private fun startExport(asJson: Boolean) {
        if (networks.isEmpty()) {
            Toast.makeText(this, "Brak danych do eksportu — najpierw wykonaj skan.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = fileStampFormat.format(Date())
        val fileName = if (asJson) "BruceIR_WifiScan_$stamp.json" else "BruceIR_WifiScan_$stamp.csv"
        if (asJson) jsonExportLauncher.launch(fileName) else csvExportLauncher.launch(fileName)
    }

    private fun writeSnapshot(uri: Uri, asJson: Boolean) {
        val records = networks.values.toList()
        if (records.isEmpty()) {
            Toast.makeText(this, "Brak danych do eksportu.", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = if (asJson) buildJsonSnapshot(records) else buildCsvSnapshot(records)
        runCatching {
            val stream = contentResolver.openOutputStream(uri) ?: error("Nie można otworzyć pliku docelowego")
            stream.bufferedWriter().use { writer -> writer.write(payload) }
        }.onSuccess {
            Toast.makeText(
                this,
                "Zapisano ${if (asJson) "JSON" else "CSV"} (${records.size} sieci).",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { failure ->
            Toast.makeText(this, "Eksport nieudany: ${failure.message ?: "błąd zapisu"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildCsvSnapshot(records: List<NetworkRecord>): String {
        val builder = StringBuilder()
        builder.appendLine(
            "ssid,bssid,frequency_mhz,channel,band,capabilities,security,rssi_dbm,signal_percent," +
                "min_rssi,max_rssi,avg_rssi,trend,history_samples,seen_count,first_seen,last_seen,hidden,risk_flags"
        )
        records.forEach { record ->
            val fields = listOf(
                displaySsid(record),
                record.bssid,
                record.frequency.toString(),
                WifiSignalUtils.channel(record.frequency),
                bandOf(record.frequency),
                record.capabilities,
                securityType(record.capabilities),
                record.level.toString(),
                WifiSignalUtils.signalPercent(record.level).toString(),
                record.minLevel().toString(),
                record.maxLevel().toString(),
                record.averageLevel().toString(),
                signalTrend(record).label,
                record.sampleCount().toString(),
                record.seenCount.toString(),
                formatDateTime(record.firstSeen),
                formatDateTime(record.lastSeen),
                if (record.ssid.isBlank()) "true" else "false",
                riskFlags(record).joinToString("|")
            )
            builder.appendLine(fields.joinToString(",") { field -> csvField(field) })
        }
        return builder.toString()
    }

    private fun buildJsonSnapshot(records: List<NetworkRecord>): String {
        val root = JSONObject()
        root.put("module", "AggressiveWifiScanActivity")
        root.put("generated_at", formatDateTime(System.currentTimeMillis()))
        root.put("networks_count", records.size)
        val networksArray = JSONArray()
        records.forEach { record ->
            val item = JSONObject()
            item.put("ssid", displaySsid(record))
            item.put("bssid", record.bssid)
            item.put("frequency_mhz", record.frequency)
            item.put("channel", WifiSignalUtils.channel(record.frequency))
            item.put("band", bandOf(record.frequency))
            item.put("capabilities", record.capabilities)
            item.put("security", securityType(record.capabilities))
            item.put("rssi_dbm", record.level)
            item.put("signal_percent", WifiSignalUtils.signalPercent(record.level))
            item.put("rssi_min", record.minLevel())
            item.put("rssi_max", record.maxLevel())
            item.put("rssi_avg", record.averageLevel())
            item.put("trend", signalTrend(record).label)
            item.put("is_hidden", record.ssid.isBlank())
            item.put("seen_count", record.seenCount)
            item.put("first_seen", formatDateTime(record.firstSeen))
            item.put("last_seen", formatDateTime(record.lastSeen))
            val flagsArray = JSONArray()
            riskFlags(record).forEach { flag -> flagsArray.put(flag) }
            item.put("risk_flags", flagsArray)
            val historyArray = JSONArray()
            record.samples.forEach { sample -> historyArray.put(sample) }
            item.put("history", historyArray)
            networksArray.put(item)
        }
        root.put("networks", networksArray)
        return root.toString(2)
    }

    private fun csvField(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    // ------------------------------------------------------------ helpers ---

    private fun formatClock(timestampMs: Long): String = clockFormat.format(Date(timestampMs))

    private fun formatDateTime(timestampMs: Long): String = dateTimeFormat.format(Date(timestampMs))

    private fun formatScanTimestamp(timestampMicros: Long): String {
        if (timestampMicros <= 0L) return "brak"
        return String.format(Locale.US, "%.3f s od uruchomienia", timestampMicros / 1_000_000.0)
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(COLOR_CARD)
        strokeColor = COLOR_STROKE
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    private fun rowLayout(centerVertical: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        if (centerVertical) gravity = Gravity.CENTER_VERTICAL
    }

    private fun columnLayout(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun filledButton(label: String, description: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun outlinedButton(label: String, description: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun LinearLayout.addLabel(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0
    ) {
        addView(text(value, size, color, bold).apply { setPadding(dp(left), dp(top), dp(right), dp(bottom)) })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Small custom view drawing the bounded RSSI history as a sparkline.
    private inner class SparklineView(context: Context) : View(context) {

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT_GREEN
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SPARK_FILL
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_STROKE
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACCENT_BLUE
            style = Paint.Style.FILL
        }
        private var values: List<Int> = emptyList()

        fun setValues(newValues: List<Int>) {
            values = newValues.toList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(3).toFloat()
            val right = width - dp(3).toFloat()
            val top = dp(5).toFloat()
            val bottom = height - dp(5).toFloat()
            canvas.drawLine(left, bottom, right, bottom, gridPaint)
            if (values.isEmpty() || right <= left || bottom <= top) return

            val minValue = values.minOrNull() ?: 0
            val maxValue = values.maxOrNull() ?: 0
            val span = (maxValue - minValue).coerceAtLeast(1).toFloat()
            val stepX = if (values.size > 1) (right - left) / (values.size - 1) else 0f
            val linePath = Path()
            values.forEachIndexed { index, value ->
                val x = if (values.size > 1) left + stepX * index else right
                val y = bottom - ((value - minValue).toFloat() / span) * (bottom - top)
                if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            if (values.size > 1) {
                val fillPath = Path(linePath)
                fillPath.lineTo(right, bottom)
                fillPath.lineTo(left, bottom)
                fillPath.close()
                canvas.drawPath(fillPath, fillPaint)
            }
            canvas.drawPath(linePath, linePaint)
            val lastIndex = values.size - 1
            val lastX = if (values.size > 1) left + stepX * lastIndex else right
            val lastY = bottom - ((values[lastIndex] - minValue).toFloat() / span) * (bottom - top)
            canvas.drawCircle(lastX, lastY, dp(2).toFloat(), dotPaint)
        }
    }
}
