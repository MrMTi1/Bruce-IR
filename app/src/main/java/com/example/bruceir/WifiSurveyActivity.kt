package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.BufferedWriter

class WifiSurveyActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var list: LinearLayout
    private lateinit var refreshButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var autoRefreshSwitch: MaterialSwitch
    private lateinit var selectedDetailsView: TextView
    private var selectedBssid: String? = null
    private var latestScanFresh = false
    private var latestResults: List<ScanResult> = emptyList()
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null || latestResults.isEmpty()) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writeCsv(writer, latestResults)
            } ?: error("Could not open CSV destination")
        }.onSuccess {
            Toast.makeText(this, R.string.wifi_survey_exported, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.wifi_survey_export_failed, Toast.LENGTH_LONG).show()
        }
    }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private var receiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable {
        if (::refreshButton.isInitialized && !refreshButton.isEnabled) {
            refreshButton.isEnabled = true
            statusView.setText(R.string.wifi_survey_timeout)
        }
    }
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (!::autoRefreshSwitch.isInitialized || !autoRefreshSwitch.isChecked || !receiverRegistered) return
            requestScan()
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestScan() else {
            statusView.setText(R.string.wifi_survey_permission_denied)
            Toast.makeText(this, R.string.wifi_survey_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            mainHandler.removeCallbacks(scanTimeout)
            renderResults(runCatching { wifiManager.scanResults }.getOrDefault(emptyList()), fresh)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.NETWORK)) return
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                scanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        requestScan()
        scheduleAutoRefresh()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(autoRefreshRunnable)
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF070A0F.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(label(getString(R.string.wifi_survey_eyebrow), 11f, 0xFF43C59E.toInt(), true))
        titles.addView(label(getString(R.string.wifi_survey_title), 25f, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.dialog_close)
            setOnClickListener { finish() }
        }
        top.addView(backButton)
        body.addView(top)

        body.addView(label(getString(R.string.wifi_survey_description), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(8), 0, dp(14))
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        refreshButton = MaterialButton(this).apply {
            text = getString(R.string.wifi_survey_scan)
            setOnClickListener { requestScan() }
        }
        val settingsButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wifi_survey_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        controls.addView(refreshButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(8)
        })
        controls.addView(settingsButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(controls)
        exportButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wifi_survey_export)
            isEnabled = false
            setOnClickListener { exportLauncher.launch("BruceIR_WiFi_Survey.csv") }
        }
        body.addView(exportButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(6)
        })

        statusView = label(getString(R.string.wifi_survey_ready), 13f, 0xFFB4C2D1.toInt()).apply {
            setPadding(dp(2), dp(12), dp(2), dp(12))
        }
        body.addView(statusView)
        selectedDetailsView = label(getString(R.string.wifi_survey_select_hint), 12f, 0xFFB7C5D4.toInt()).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        body.addView(selectedDetailsView, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        autoRefreshSwitch = MaterialSwitch(this).apply {
            text = getString(R.string.wifi_survey_auto_refresh)
            setTextColor(0xFFB7C5D4.toInt())
            setOnCheckedChangeListener { _, _ -> scheduleAutoRefresh() }
        }
        body.addView(autoRefreshSwitch, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(list)
    }

    private fun scheduleAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable)
        if (::autoRefreshSwitch.isInitialized && autoRefreshSwitch.isChecked && receiverRegistered) {
            mainHandler.postDelayed(autoRefreshRunnable, 30_000L)
        }
    }

    private fun requestScan() {
        if (!::statusView.isInitialized || !refreshButton.isEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText(R.string.wifi_survey_permission_needed)
            permissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        if (!wifiManager.isWifiEnabled) {
            statusView.setText(R.string.wifi_survey_wifi_off)
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && locationManager?.isLocationEnabled != true) {
            statusView.setText(R.string.wifi_survey_location_off)
            return
        }
        refreshButton.isEnabled = false
        statusView.setText(R.string.wifi_survey_scanning)
        val requested = runCatching { wifiManager.startScan() }.getOrDefault(false)
        if (requested) {
            mainHandler.postDelayed(scanTimeout, 12_000L)
        } else {
            refreshButton.isEnabled = true
            statusView.setText(R.string.wifi_survey_cached)
            @SuppressLint("MissingPermission")
            val cached = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
            renderResults(cached, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderResults(scanResults: List<ScanResult>, fresh: Boolean) {
        mainHandler.removeCallbacks(scanTimeout)
        refreshButton.isEnabled = true
        val unique = scanResults
            .filter { it.BSSID.isNotBlank() }
            .distinctBy { it.BSSID }
            .sortedByDescending { it.level }
        latestResults = unique
        latestScanFresh = fresh
        exportButton.isEnabled = unique.isNotEmpty()
        updateSelectedTarget()
        val status = if (fresh) {
            getString(R.string.wifi_survey_result_fresh, unique.size)
        } else {
            getString(R.string.wifi_survey_result_cached, unique.size)
        }
        statusView.text = status
        list.removeAllViews()
        if (unique.isEmpty()) {
            list.addView(label(getString(R.string.wifi_survey_empty), 14f, 0xFF9EADBD.toInt()).apply {
                setPadding(dp(6), dp(14), dp(6), dp(14))
            })
            return
        }
        unique.forEach { result -> list.addView(networkCard(result)) }
    }

    private fun networkCard(result: ScanResult): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(0xFF141B25.toInt())
            strokeColor = if (selectedBssid == result.BSSID) 0xFF43C59E.toInt() else 0xFF283545.toInt()
            strokeWidth = dp(1)
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedBssid = result.BSSID
                updateSelectedTarget()
                for (index in 0 until list.childCount) {
                    val item = list.getChildAt(index) as? MaterialCardView ?: continue
                    item.strokeColor = if (item.tag == result.BSSID) 0xFF43C59E.toInt() else 0xFF283545.toInt()
                }
            }
            tag = result.BSSID
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val ssid = result.SSID.takeIf { it.isNotBlank() } ?: getString(R.string.wifi_survey_hidden)
        column.addView(label(ssid, 16f, 0xFFFFFFFF.toInt(), true))
        val percent = WifiSignalUtils.signalPercent(result.level)
        val summary = getString(
            R.string.wifi_survey_network_summary,
            WifiSignalUtils.signalGauge(percent),
            result.level,
            WifiSignalUtils.channel(result.frequency),
            securityLabel(result.capabilities)
        )
        column.addView(label(summary, 12f, 0xFFB7C5D4.toInt()).apply {
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(column)
        return card
    }

    private fun updateSelectedTarget() {
        val bssid = selectedBssid ?: run {
            selectedDetailsView.setText(R.string.wifi_survey_select_hint)
            return
        }
        val result = latestResults.firstOrNull { it.BSSID == bssid }
        if (result == null) {
            selectedDetailsView.text = getString(R.string.wifi_survey_target_missing)
            return
        }
        val ssid = result.SSID.takeIf { it.isNotBlank() } ?: getString(R.string.wifi_survey_hidden)
        val freshness = getString(if (latestScanFresh) R.string.wifi_survey_scan_fresh else R.string.wifi_survey_scan_cached)
        selectedDetailsView.text = getString(
            R.string.wifi_survey_target_details,
            ssid,
            result.BSSID,
            result.level,
            WifiSignalUtils.signalPercent(result.level),
            WifiSignalUtils.channel(result.frequency),
            result.frequency,
            securityLabel(result.capabilities),
            freshness
        )
    }

    private fun showDetails(result: ScanResult) {
        val percent = WifiSignalUtils.signalPercent(result.level)
        val ssid = result.SSID.takeIf { it.isNotBlank() } ?: getString(R.string.wifi_survey_hidden)
        val quality = WifiSignalUtils.qualityText(percent)
        val message = getString(
            R.string.wifi_survey_network_details,
            ssid,
            result.BSSID,
            result.frequency,
            WifiSignalUtils.channel(result.frequency),
            result.level,
            percent,
            quality,
            WifiSignalUtils.estimatedDistanceMeters(result.level, result.frequency),
            securityLabel(result.capabilities),
            result.capabilities.ifBlank { getString(R.string.wifi_survey_security_unknown) }
        )
        AlertDialog.Builder(this)
            .setTitle(ssid)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun writeCsv(writer: BufferedWriter, results: List<ScanResult>) {
        writer.appendLine("ssid,bssid,frequency_mhz,channel,rssi_dbm,signal_percent,security,capabilities")
        results.forEach { result ->
            val percent = WifiSignalUtils.signalPercent(result.level)
            val fields = listOf(
                result.SSID.takeIf { it.isNotBlank() } ?: getString(R.string.wifi_survey_hidden),
                result.BSSID,
                result.frequency.toString(),
                WifiSignalUtils.channel(result.frequency),
                result.level.toString(),
                percent.toString(),
                securityLabel(result.capabilities),
                result.capabilities
            )
            writer.appendLine(fields.joinToString(",") { csv(it) })
        }
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun securityLabel(capabilities: String): String {
        val flags = capabilities.uppercase()
        return when {
            flags.contains("WPA3") -> "WPA3"
            flags.contains("WPA2") -> "WPA2"
            flags.contains("WPA") -> "WPA"
            flags.contains("WEP") -> "WEP"
            flags.isBlank() -> getString(R.string.wifi_survey_security_unknown)
            else -> getString(R.string.wifi_survey_open)
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
