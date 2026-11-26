package com.example.bruceir

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LanMapperActivity : AppCompatActivity() {
    private data class PortInfo(val port: Int, val service: String)
    private data class HostResult(val address: String, val ports: List<PortInfo>)
    private data class ScanRange(val cidr: String, val hosts: List<String>)
    private class MapperError(val messageResource: Int) : Exception()

    private val portsToCheck = listOf(
        PortInfo(22, "SSH"), PortInfo(53, "DNS"), PortInfo(80, "HTTP"),
        PortInfo(443, "HTTPS"), PortInfo(445, "SMB"), PortInfo(554, "RTSP"),
        PortInfo(8000, "HTTP-alt"), PortInfo(8080, "HTTP-alt"), PortInfo(8443, "HTTPS-alt")
    )
    private val extendedPorts = portsToCheck + listOf(
        PortInfo(21, "FTP"), PortInfo(23, "Telnet"), PortInfo(25, "SMTP"),
        PortInfo(110, "POP3"), PortInfo(143, "IMAP"), PortInfo(389, "LDAP"),
        PortInfo(515, "LPD"), PortInfo(631, "IPP"), PortInfo(1883, "MQTT"),
        PortInfo(3389, "RDP"), PortInfo(5000, "HTTP-alt"), PortInfo(5001, "HTTPS-alt"),
        PortInfo(5900, "VNC"), PortInfo(8883, "MQTT-TLS"), PortInfo(9100, "Printer"),
        PortInfo(9200, "Elasticsearch")
    )
    private lateinit var statusView: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var profileButton: MaterialButton
    private lateinit var resultList: LinearLayout
    private var extendedMode = false
    private var scanJob: Job? = null
    private var results: List<HostResult> = emptyList()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null || results.isEmpty()) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writeCsv(writer, results)
            } ?: error("Could not open CSV destination")
        }.onSuccess {
            Toast.makeText(this, R.string.mapper_exported, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.mapper_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.NETWORK)) return
        window.statusBarColor = 0xFF070A0F.toInt()
        window.navigationBarColor = 0xFF070A0F.toInt()
        buildScreen()
    }

    override fun onStop() {
        scanJob?.cancel()
        super.onStop()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF070A0F.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 24f
            minWidth = dp(48)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleStack.addView(label(getString(R.string.mapper_eyebrow), 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(label(getString(R.string.mapper_title), 24f, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleStack)
        content.addView(header)

        content.addView(label(getString(R.string.mapper_description), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(10), 0, dp(12))
        })

        val scopeCard = card()
        scopeCard.addView(label(
            getString(R.string.mapper_scope, getString(R.string.mapper_waiting)),
            12f,
            0xFFB4C2D1.toInt()
        ))
        content.addView(scopeCard, verticalParams(bottom = 10))

        statusView = label(getString(R.string.mapper_ready), 13f, 0xFFB4C2D1.toInt()).apply {
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        content.addView(statusView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scanButton = MaterialButton(this).apply {
            text = getString(R.string.mapper_scan)
            setOnClickListener { toggleScan() }
        }
        exportButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.mapper_export)
            isEnabled = false
            setOnClickListener { exportLauncher.launch("BruceIR_Network_Map.csv") }
        }
        controls.addView(scanButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(8)
        })
        controls.addView(exportButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(controls)
        profileButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.mapper_profile_quick)
            setOnClickListener {
                extendedMode = !extendedMode
                text = getString(if (extendedMode) R.string.mapper_profile_extended else R.string.mapper_profile_quick)
            }
        }
        content.addView(profileButton, verticalParams(top = 6))
        content.addView(label(getString(R.string.mapper_profile_hint), 11f, 0xFF8792A2.toInt()).apply {
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        resultList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(resultList, verticalParams(top = 14))
    }

    private fun toggleScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            return
        }
        results = emptyList()
        resultList.removeAllViews()
        exportButton.isEnabled = false
        statusView.setText(R.string.mapper_waiting)
        scanButton.text = getString(R.string.mapper_stop)
        val found = ConcurrentHashMap<String, List<PortInfo>>()
        val ports = if (extendedMode) extendedPorts else portsToCheck
        profileButton.isEnabled = false
        var scannedCidr: String? = null
        scanJob = lifecycleScope.launch {
            try {
                val range = withContext(Dispatchers.IO) { findScanRange() }
                scannedCidr = range.cidr
                val completed = AtomicInteger(0)
                val total = range.hosts.size
                statusView.text = getString(R.string.mapper_scanning, range.cidr, 0, total)
                val semaphore = Semaphore(32)
                coroutineScope {
                    range.hosts.map { ip ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                currentCoroutineContext().ensureActive()
                                val openPorts = mutableListOf<PortInfo>()
                                for (port in ports) {
                                    currentCoroutineContext().ensureActive()
                                    if (probe(ip, port.port)) openPorts += port
                                }
                                if (openPorts.isNotEmpty()) found[ip] = openPorts
                                val done = completed.incrementAndGet()
                                if (done % 8 == 0 || done == total) {
                                    withContext(Dispatchers.Main) {
                                        statusView.text = getString(R.string.mapper_scanning, range.cidr, done, total)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
                displayResults(range.cidr, found)
            } catch (error: MapperError) {
                statusView.setText(error.messageResource)
            } catch (_: CancellationException) {
                displayResults(scannedCidr, found, canceled = true)
            } catch (_: Exception) {
                statusView.setText(R.string.mapper_subnet_unsupported)
            } finally {
                scanButton.text = getString(R.string.mapper_scan)
                profileButton.isEnabled = true
                scanJob = null
            }
        }
    }

    private fun findScanRange(): ScanRange {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val capabilities = active?.let(manager::getNetworkCapabilities)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            throw MapperError(R.string.mapper_wifi_required)
        }
        val linkProperties = active?.let(manager::getLinkProperties)
            ?: throw MapperError(R.string.mapper_wifi_required)
        val link = linkProperties.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: throw MapperError(R.string.mapper_wifi_required)
        val address = link.address as Inet4Address
        if (!isRfc1918(address.address)) throw MapperError(R.string.mapper_private_required)

        val prefix = link.prefixLength
        if (prefix !in 8..30) throw MapperError(R.string.mapper_subnet_unsupported)
        val scanPrefix = maxOf(prefix, 24)
        val ipValue = addressToLong(address.address)
        val mask = (0xFFFFFFFFL shl (32 - scanPrefix)) and 0xFFFFFFFFL
        val first = ipValue and mask
        val size = 1L shl (32 - scanPrefix)
        val last = first + size - 1
        if (size - 2 > 254) throw MapperError(R.string.mapper_subnet_unsupported)
        val hosts = (first + 1 until last)
            .filter { it != ipValue }
            .map(::longToIp)
        if (hosts.isEmpty()) throw MapperError(R.string.mapper_subnet_unsupported)
        return ScanRange("${longToIp(first)}/$scanPrefix", hosts)
    }

    private fun isRfc1918(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun addressToLong(bytes: ByteArray): Long = bytes.fold(0L) { result, byte ->
        (result shl 8) or (byte.toLong() and 0xFF)
    }

    private fun longToIp(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }

    private fun probe(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 220)
                true
            }
        }.getOrDefault(false)
    }

    private fun isWebPort(port: Int): Boolean {
        return port in setOf(80, 81, 82, 83, 84, 85, 443, 3000, 4443, 5000, 5001, 8000, 8008, 8080, 8081, 8088, 8090, 8123, 8443, 8888, 9000, 9443, 32400)
    }

    private fun getWebUrl(ip: String, port: Int): String {
        val scheme = if (port in setOf(443, 4443, 8443, 9443, 5001)) "https" else "http"
        val portStr = if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) "" else ":$port"
        return "$scheme://$ip$portStr"
    }

    private fun openUrlOptions(url: String) {
        val options = arrayOf("📱 Otwórz w aplikacji Bruce (WebView)", "🌐 Otwórz w przeglądarce systemowej")
        AlertDialog.Builder(this)
            .setTitle("🌐 Otwórz usługę Web\n$url")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val intent = Intent(this, BruceWebActivity::class.java).apply {
                        putExtra("EXTRA_URL", url)
                    }
                    startActivity(intent)
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Nie można otworzyć przeglądarki: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun displayResults(
        cidr: String?,
        found: Map<String, List<PortInfo>>,
        canceled: Boolean = false
    ) {
        results = found.map { (ip, ports) -> HostResult(ip, ports) }
            .sortedBy { host ->
                host.address.split(".").map { it.toInt() }.fold(0L) { value: Long, part: Int ->
                    (value shl 8) + part.toLong()
                }
            }
        exportButton.isEnabled = results.isNotEmpty()
        resultList.removeAllViews()
        statusView.text = when {
            canceled -> getString(R.string.mapper_stopped)
            results.isEmpty() -> getString(R.string.mapper_no_results)
            else -> getString(R.string.mapper_results, results.size, cidr.orEmpty())
        }
        results.forEach { host ->
            val card = card().apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { showHost(host) }
            }
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val openPorts = host.ports.joinToString(", ") { "${it.port}/${it.service}" }
            container.addView(label(getString(R.string.mapper_host_ports, host.address, openPorts), 14f, 0xFFFFFFFF.toInt(), true))

            val webPorts = host.ports.filter { isWebPort(it.port) }
            if (webPorts.isNotEmpty()) {
                val webRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, 0)
                }
                webPorts.take(3).forEach { p ->
                    val url = getWebUrl(host.address, p.port)
                    val btnWeb = MaterialButton(this).apply {
                        text = "🌐 ${p.port}"
                        textSize = 10f
                        setPadding(dp(6), 0, dp(6), 0)
                        setBackgroundColor(0xFF43C59E.toInt())
                        setTextColor(Color.BLACK)
                        setOnClickListener { openUrlOptions(url) }
                    }
                    webRow.addView(btnWeb, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
                }
                container.addView(webRow)
            }

            card.addView(container)
            resultList.addView(card, verticalParams(bottom = 8))
        }
    }

    private fun showHost(host: HostResult) {
        val openPorts = host.ports.joinToString("\n") { "${it.port}/tcp · ${it.service}" }
        val webPorts = host.ports.filter { isWebPort(it.port) }

        val builder = AlertDialog.Builder(this)
            .setTitle(host.address)
            .setMessage(
                getString(R.string.mapper_host_details, host.address, openPorts) +
                    "\n\n" + getString(R.string.mapper_deep_scan_scope)
            )
            .setPositiveButton(R.string.dialog_close, null)
            .setNegativeButton(R.string.mapper_rescan_host) { _, _ -> rescanHost(host.address) }

        if (webPorts.isNotEmpty()) {
            val firstUrl = getWebUrl(host.address, webPorts.first().port)
            builder.setNeutralButton("🌐 Otwórz Web ($firstUrl)") { _, _ -> openUrlOptions(firstUrl) }
        } else {
            builder.setNeutralButton(R.string.mapper_deep_scan_host) { _, _ -> rescanHost(host.address, deepScan = true) }
        }
        builder.show()
    }

    private fun rescanHost(address: String, deepScan: Boolean = false) {
        if (scanJob?.isActive == true) return
        val knownServices = extendedPorts.associateBy { it.port }
        val ports = if (deepScan) {
            (1..1024).map { port -> knownServices[port] ?: PortInfo(port, "TCP") }
        } else if (extendedMode) {
            extendedPorts
        } else {
            portsToCheck
        }
        scanButton.isEnabled = true
        scanButton.setText(R.string.mapper_stop)
        profileButton.isEnabled = false
        statusView.setText(R.string.mapper_host_scanning)
        scanJob = lifecycleScope.launch {
            try {
                val range = withContext(Dispatchers.IO) { findScanRange() }
                if (address !in range.hosts) throw MapperError(R.string.mapper_subnet_unsupported)
                val completed = AtomicInteger(0)
                val semaphore = Semaphore(32)
                val openPorts = coroutineScope {
                    ports.map { port ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val isOpen = probe(address, port.port)
                                val done = completed.incrementAndGet()
                                if (deepScan && (done % 64 == 0 || done == ports.size)) {
                                    withContext(Dispatchers.Main) {
                                        statusView.text = getString(
                                            R.string.mapper_deep_scan_progress,
                                            address,
                                            done,
                                            ports.size
                                        )
                                    }
                                }
                                if (isOpen) port else null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val updated = results.filterNot { it.address == address }.toMutableList()
                if (openPorts.isNotEmpty()) updated += HostResult(address, openPorts)
                displayResults(range.cidr, updated.associate { it.address to it.ports })
                if (openPorts.isEmpty()) statusView.setText(R.string.mapper_host_empty)
            } catch (error: MapperError) {
                statusView.setText(error.messageResource)
            } catch (_: CancellationException) {
                statusView.setText(R.string.mapper_stopped)
            } catch (_: Exception) {
                statusView.setText(R.string.mapper_subnet_unsupported)
            } finally {
                scanButton.setText(R.string.mapper_scan)
                scanButton.isEnabled = true
                profileButton.isEnabled = true
                scanJob = null
            }
        }
    }

    private fun writeCsv(writer: BufferedWriter, hosts: List<HostResult>) {
        writer.appendLine("ip,open_tcp_ports,services")
        hosts.forEach { host ->
            val portList = host.ports.joinToString(";") { it.port.toString() }
            val services = host.ports.joinToString(";") { it.service }
            writer.appendLine("${csv(host.address)},${csv(portList)},${csv(services)}")
        }
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(0xFF141B25.toInt())
        strokeColor = 0xFF283545.toInt()
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun verticalParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
        bottomMargin = bottom
    }
}
