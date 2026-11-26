package com.example.bruceir

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToLong

class AdvancedNetworkScannerActivity : AppCompatActivity() {

    private data class OpenPort(
        val port: Int,
        val service: String,
        val latencyMs: Long,
        val banner: String?,
        val tls: Boolean
    )

    private data class HostResult(
        val ip: String,
        val mac: String?,
        val vendor: String?,
        val deviceType: String,
        val openPorts: List<OpenPort>,
        val hasArpEntry: Boolean,
        val avgLatencyMs: Long
    )

    private data class ProbeOutcome(val openPort: OpenPort?, val timedOut: Boolean)

    private data class FingerprintInfo(val banner: String?, val tls: Boolean)

    private data class NetworkContext(
        val interfaceName: String,
        val transport: String,
        val localIp: String,
        val prefixLength: Int,
        val scanCidr: String,
        val autoHostCount: Int
    )

    private data class ScanTarget(val label: String, val hosts: List<String>)

    private data class CidrSpec(val networkValue: Long, val prefix: Int, val hosts: List<String>)

    private data class SsdpDevice(
        val address: String,
        val server: String,
        val location: String,
        val usn: String,
        val searchTarget: String
    )

    private enum class TargetMode { AUTO, CIDR, SINGLE }

    private enum class PortProfile { QUICK, TOP100, CUSTOM }

    private class ScannerException(message: String) : Exception(message)

    private val colorBackground = 0xFF070A0F.toInt()
    private val colorSurface = 0xFF141B25.toInt()
    private val colorSurfaceAlt = 0xFF1B2430.toInt()
    private val colorStroke = 0xFF283545.toInt()
    private val colorAccent = 0xFF43C59E.toInt()
    private val colorAccentBlue = 0xFF00E5FF.toInt()
    private val colorMuted = 0xFF9EADBD.toInt()
    private val colorMutedDim = 0xFF748397.toInt()
    private val colorText = 0xFFFFFFFF.toInt()
    private val colorDanger = 0xFFFF6B6B.toInt()
    private val colorOnAccent = 0xFF07120E.toInt()

    private val quickPorts = listOf(22, 23, 53, 80, 443, 445, 554, 3389, 8080, 8443, 9100, 5000)

    private val topPorts = listOf(
        21, 22, 23, 25, 53, 67, 69, 80, 81, 82, 83, 84, 85, 88, 110, 111, 123, 135, 137, 138,
        139, 143, 161, 162, 179, 199, 389, 427, 443, 444, 445, 465, 500, 512, 513, 514, 515, 524,
        548, 554, 587, 623, 631, 636, 873, 902, 990, 993, 995, 1025, 1080, 1194, 1433, 1521,
        1701, 1723, 1755, 1812, 1883, 1900, 2049, 2082, 2083, 2181, 2222, 2375, 2376, 3000, 3128,
        3260, 3306, 3389, 3690, 4444, 5000, 5001, 5060, 5222, 5432, 5555, 5601, 5672, 5800, 5900,
        5984, 6379, 6443, 7000, 8000, 8080, 8081, 8443, 8888, 9000, 9090, 9100, 9200, 11211, 1935, 27017
    )

    private val serviceNames: Map<Int, String> = mapOf(
        20 to "FTP-DATA", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 67 to "DHCP", 69 to "TFTP", 80 to "HTTP", 81 to "HTTP-alt",
        88 to "Kerberos", 110 to "POP3", 111 to "RPCbind", 123 to "NTP", 135 to "MS-RPC",
        137 to "NetBIOS-NS", 138 to "NetBIOS-DGM", 139 to "NetBIOS-SSN", 143 to "IMAP",
        161 to "SNMP", 162 to "SNMP-trap", 179 to "BGP", 389 to "LDAP", 443 to "HTTPS",
        445 to "SMB", 465 to "SMTPS", 500 to "IKE", 514 to "Syslog", 515 to "LPD",
        548 to "AFP", 554 to "RTSP", 587 to "SMTP-sub", 623 to "IPMI", 631 to "IPP",
        636 to "LDAPS", 873 to "rsync", 902 to "VMware", 990 to "FTPS", 993 to "IMAPS",
        995 to "POP3S", 1025 to "MS-RPC", 1080 to "SOCKS", 1194 to "OpenVPN",
        1433 to "MSSQL", 1521 to "Oracle", 1701 to "L2TP", 1723 to "PPTP", 1755 to "MMS",
        1812 to "RADIUS", 1883 to "MQTT", 1900 to "SSDP", 1935 to "RTMP", 2049 to "NFS", 2082 to "cPanel",
        2083 to "cPanel-SSL", 2181 to "ZooKeeper", 2222 to "SSH-alt", 2375 to "Docker",
        2376 to "Docker-TLS", 3000 to "HTTP-dev", 3128 to "Squid", 3260 to "iSCSI",
        3306 to "MySQL", 3389 to "RDP", 3690 to "SVN", 4444 to "MSF/nc", 5000 to "HTTP-alt",
        5001 to "HTTPS-alt", 5060 to "SIP", 5222 to "XMPP", 5432 to "PostgreSQL",
        5555 to "ADB", 5601 to "Kibana", 5672 to "AMQP", 5800 to "VNC-alt",
        5900 to "VNC", 5984 to "CouchDB", 6379 to "Redis", 6443 to "HTTPS-alt",
        7000 to "AirPlay", 8000 to "HTTP-alt", 8008 to "HTTP-alt", 8080 to "HTTP-alt",
        8081 to "HTTP-alt", 8088 to "HTTP-alt", 8123 to "HomeAssistant",
        8443 to "HTTPS-alt", 8554 to "RTSP-alt", 8883 to "MQTT-TLS", 8888 to "HTTP-alt",
        9000 to "HTTP-alt", 9090 to "Prometheus", 9100 to "Printer", 9200 to "Elasticsearch",
        9443 to "HTTPS-alt", 11211 to "Memcached", 27017 to "MongoDB", 32400 to "Plex",
        49152 to "UPnP", 62078 to "iPhone-sync"
    )

    private val ouiVendors: Map<String, String> = mapOf(
        "00:03:93" to "Apple", "00:0A:27" to "Apple", "00:1B:63" to "Apple",
        "00:1F:F3" to "Apple", "00:23:12" to "Apple", "00:25:00" to "Apple",
        "A4:83:E7" to "Apple", "F0:18:98" to "Apple",
        "00:12:FB" to "Samsung", "00:15:99" to "Samsung", "00:24:54" to "Samsung",
        "34:23:BA" to "Samsung", "5C:0A:5B" to "Samsung", "8C:77:12" to "Samsung",
        "E8:50:8B" to "Samsung",
        "00:9E:C8" to "Xiaomi", "28:6C:07" to "Xiaomi", "64:B4:73" to "Xiaomi",
        "78:11:DC" to "Xiaomi", "F0:B4:29" to "Xiaomi",
        "00:E0:FC" to "Huawei", "00:25:9E" to "Huawei", "48:46:FB" to "Huawei",
        "70:72:3C" to "Huawei", "84:A8:E4" to "Huawei",
        "00:1D:0F" to "TP-Link", "14:CC:20" to "TP-Link", "50:C7:BF" to "TP-Link",
        "98:DA:C4" to "TP-Link", "A4:2B:B0" to "TP-Link", "EC:08:6B" to "TP-Link",
        "00:1F:C6" to "ASUS", "04:D4:C4" to "ASUS", "1C:87:2C" to "ASUS",
        "2C:56:DC" to "ASUS", "38:D5:47" to "ASUS", "AC:22:0B" to "ASUS",
        "00:14:6C" to "Netgear", "00:1B:2F" to "Netgear", "20:4E:7F" to "Netgear",
        "44:94:FC" to "Netgear", "A0:40:A0" to "Netgear", "C0:3F:0E" to "Netgear",
        "00:1B:11" to "D-Link", "00:22:B0" to "D-Link", "1C:7E:E5" to "D-Link",
        "28:10:7B" to "D-Link", "78:32:1B" to "D-Link", "B8:A3:86" to "D-Link",
        "00:00:0C" to "Cisco", "00:1A:A1" to "Cisco", "00:26:0B" to "Cisco",
        "2C:3F:38" to "Cisco", "58:97:BD" to "Cisco",
        "04:18:D6" to "Ubiquiti", "24:5A:4C" to "Ubiquiti", "44:D9:E7" to "Ubiquiti",
        "68:D7:9A" to "Ubiquiti", "78:8A:20" to "Ubiquiti", "F0:9F:C2" to "Ubiquiti",
        "00:0C:42" to "MikroTik", "2C:C8:1B" to "MikroTik", "48:8F:5A" to "MikroTik",
        "64:D1:54" to "MikroTik", "74:4D:28" to "MikroTik", "CC:2D:E0" to "MikroTik",
        "B8:27:EB" to "Raspberry Pi", "DC:A6:32" to "Raspberry Pi",
        "E4:5F:01" to "Raspberry Pi", "2C:CF:67" to "Raspberry Pi",
        "24:0A:C4" to "Espressif", "24:6F:28" to "Espressif", "3C:71:BF" to "Espressif",
        "5C:CF:7F" to "Espressif", "84:F3:EB" to "Espressif", "08:3A:F2" to "Espressif",
        "DC:4F:22" to "Sonoff / ITEAD",
        "00:17:88" to "Philips Hue", "EC:B5:FA" to "Philips Hue",
        "44:65:0D" to "Amazon", "68:37:E9" to "Amazon", "74:C2:46" to "Amazon",
        "88:71:E5" to "Amazon", "AC:63:BE" to "Amazon", "F0:27:2D" to "Amazon",
        "00:1A:11" to "Google", "3C:5A:B4" to "Google", "F4:F5:D8" to "Google",
        "94:EB:2C" to "Google", "A4:77:33" to "Google", "D8:6C:63" to "Google",
        "00:0E:58" to "Sonos", "5C:AA:FD" to "Sonos", "78:28:CA" to "Sonos",
        "B8:E9:37" to "Sonos",
        "00:1B:21" to "Intel", "00:1E:64" to "Intel", "3C:97:0E" to "Intel",
        "6C:88:14" to "Intel", "A4:BB:6D" to "Intel",
        "00:E0:4C" to "Realtek",
        "00:10:18" to "Broadcom", "00:1B:E9" to "Broadcom",
        "00:1C:4A" to "AVM (Fritz!Box)", "3C:A6:2F" to "AVM (Fritz!Box)",
        "00:11:32" to "Synology", "90:09:D0" to "Synology",
        "00:08:9B" to "QNAP", "24:5E:BE" to "QNAP",
        "00:90:A9" to "Western Digital", "00:14:EE" to "Western Digital",
        "44:19:B6" to "Hikvision", "4C:BD:8F" to "Hikvision", "C0:56:E3" to "Hikvision",
        "3C:EF:8C" to "Dahua", "90:02:A9" to "Dahua",
        "00:80:77" to "Brother", "30:05:5C" to "Brother",
        "00:1F:29" to "HP (Hewlett-Packard)", "3C:D9:2B" to "HP (Hewlett-Packard)",
        "00:14:22" to "Dell", "B8:CA:3A" to "Dell",
        "00:13:A9" to "Sony", "FC:0F:E6" to "Sony",
        "00:09:BF" to "Nintendo", "40:D4:0E" to "Nintendo",
        "00:04:4B" to "NVIDIA", "48:B0:2D" to "NVIDIA",
        "00:12:37" to "Texas Instruments",
        "00:0C:29" to "VMware",
        "00:50:F2" to "Microsoft"
    )

    private val httpPorts = setOf(80, 81, 82, 83, 84, 85, 3000, 5000, 8000, 8008, 8080, 8081, 8088, 8090, 8123, 8888, 9000, 9090, 9200)
    private val tlsPorts = setOf(443, 4443, 6443, 8443, 9443, 10443)
    private val greetingPorts = setOf(21, 22, 23, 25, 110, 143, 587)
    private val rtspPorts = setOf(554, 8554)

    private val timeoutOptions = listOf(200, 400, 800, 1500)

    private val modeButtons = mutableListOf<MaterialButton>()
    private val profileButtons = mutableListOf<MaterialButton>()
    private val timeoutButtons = mutableListOf<MaterialButton>()

    private lateinit var networkSummary: TextView
    private lateinit var networkDetail: TextView
    private lateinit var targetInput: EditText
    private lateinit var targetHint: TextView
    private lateinit var profileInfo: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var ssdpButton: MaterialButton
    private lateinit var ssdpStatus: TextView
    private lateinit var ssdpContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var sortButton: MaterialButton
    private lateinit var resultsCountView: TextView
    private lateinit var exportCsvButton: MaterialButton
    private lateinit var exportJsonButton: MaterialButton
    private lateinit var resultsContainer: LinearLayout
    private lateinit var emptyView: TextView

    private var targetMode = TargetMode.AUTO
    private var portProfile = PortProfile.QUICK
    private var customPortList: List<Int> = emptyList()
    private var selectedTimeoutMs = 400
    private var scanJob: Job? = null
    private var ssdpJob: Job? = null
    private var elapsedJob: Job? = null
    private var results: List<HostResult> = emptyList()
    private var scannedSubnetLabel: String = ""
    private var scanUnitLabel: String = "hostów"
    private var networkContext: NetworkContext? = null
    private var activeTargetHosts: List<String> = emptyList()
    private var searchQuery: String = ""
    private var sortAscending = true

    private val scanStartedAt = AtomicLong(0)
    private val scannedHosts = AtomicInteger(0)
    private val openPortsFound = AtomicInteger(0)
    private val lastProgressPublish = AtomicLong(0)
    private val activeDiscovery = ConcurrentHashMap<String, MutableList<OpenPort>>()

    private val csvExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) exportResults(uri, json = false)
    }

    private val jsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) exportResults(uri, json = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.NETWORK)) return
        window.statusBarColor = colorBackground
        window.navigationBarColor = colorBackground
        buildScreen()
        applyWindowInsets()
        refreshNetworkContext()
    }

    override fun onStop() {
        scanJob?.cancel()
        ssdpJob?.cancel()
        elapsedJob?.cancel()
        elapsedJob = null
        super.onStop()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        ssdpJob?.cancel()
        elapsedJob?.cancel()
        elapsedJob = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyWindowInsets() {
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    // ---------------------------------------------------------------- UI

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(colorBackground)
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = outlinedButton("‹").apply {
            textSize = 24f
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("SIEĆ LAN", 10f, colorAccent, true))
        titleStack.addView(text("Advanced Network Scanner", 24f, colorText, true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        val info = outlinedButton("?").apply { setOnClickListener { showInfoDialog() } }
        header.addView(info, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(header)

        root.addView(text(
            "Wykrywanie hostów, portów i usług w sieci lokalnej · TCP connect + ARP + OUI",
            12.5f,
            colorMuted
        ).apply { setPadding(0, dp(8), 0, dp(12)) })

        root.addView(buildNetworkCard(), verticalParams(bottom = 10))
        root.addView(buildTargetCard(), verticalParams(bottom = 10))
        root.addView(buildScanCard(), verticalParams(bottom = 10))
        root.addView(buildSsdpCard(), verticalParams(bottom = 10))
        buildResultsSection(root)

        selectMode(TargetMode.AUTO)
        selectProfile(PortProfile.QUICK)
        selectTimeout(400)
    }

    private fun buildNetworkCard(): MaterialCardView {
        val card = card()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(column)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(sectionTitle("WYKRYTA SIEĆ"), LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = outlinedButton("ODŚWIEŻ").apply {
            setOnClickListener { refreshNetworkContext() }
        }
        topRow.addView(refresh, LinearLayout.LayoutParams(dp(92), dp(34)))
        column.addView(topRow)
        networkSummary = text("Wykrywanie aktywnego interfejsu...", 12.5f, colorMuted).apply {
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(networkSummary)
        networkDetail = text(
            "Wymagany prywatny adres IPv4 (RFC1918) na Wi-Fi lub Ethernet.",
            11f,
            colorMutedDim
        ).apply { setPadding(0, dp(4), 0, 0) }
        column.addView(networkDetail)
        return card
    }

    private fun buildTargetCard(): MaterialCardView {
        val card = card()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(column)
        column.addView(sectionTitle("CEL SKANOWANIA"))

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TargetMode.entries.forEachIndexed { index, mode ->
            val button = segmentedButton(when (mode) {
                TargetMode.AUTO -> "AUTO"
                TargetMode.CIDR -> "CIDR"
                TargetMode.SINGLE -> "JEDEN IP"
            }).apply { setOnClickListener { selectMode(mode) } }
            modeButtons += button
            modeRow.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        column.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        targetInput = editTextView("192.168.1.0/24").apply { visibility = View.GONE }
        column.addView(targetInput, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        targetHint = text("", 10.5f, colorMutedDim).apply { setPadding(dp(2), dp(5), dp(2), 0) }
        column.addView(targetHint)

        column.addView(separator(), separatorParams())
        column.addView(sectionTitle("PROFIL PORTÓW"))
        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PortProfile.entries.forEachIndexed { index, profile ->
            val button = segmentedButton(when (profile) {
                PortProfile.QUICK -> "Szybki"
                PortProfile.TOP100 -> "Top-100"
                PortProfile.CUSTOM -> "Własny"
            }).apply { setOnClickListener { selectProfile(profile) } }
            profileButtons += button
            profileRow.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        column.addView(profileRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        profileInfo = text("", 10.5f, colorMutedDim).apply { setPadding(dp(2), dp(5), dp(2), 0) }
        column.addView(profileInfo)

        column.addView(separator(), separatorParams())
        column.addView(sectionTitle("TIMEOUT POŁĄCZENIA (ms)"))
        val timeoutRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeoutOptions.forEachIndexed { index, value ->
            val button = segmentedButton(value.toString()).apply {
                setOnClickListener { selectTimeout(value) }
            }
            timeoutButtons += button
            timeoutRow.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        column.addView(timeoutRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return card
    }

    private fun buildScanCard(): MaterialCardView {
        val card = card()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(column)
        scanButton = MaterialButton(this).apply {
            text = "ROZPOCZNIJ SKAN"
            textSize = 14f
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(colorAccent)
            setTextColor(colorOnAccent)
            setOnClickListener { toggleScan() }
        }
        column.addView(scanButton, LinearLayout.LayoutParams(-1, dp(52)))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1
            progress = 0
            progressTintList = ColorStateList.valueOf(colorAccent)
            progressBackgroundTintList = ColorStateList.valueOf(colorSurfaceAlt)
        }
        column.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
        statusView = text(
            "Gotowy do skanowania. Wybierz cel i profil portów.",
            12.5f,
            0xFFB4C2D1.toInt()
        ).apply { setPadding(0, dp(10), 0, 0) }
        column.addView(statusView)
        elapsedView = text("", 11.5f, colorAccentBlue).apply { setPadding(0, dp(4), 0, 0) }
        column.addView(elapsedView)
        return card
    }

    private fun buildSsdpCard(): MaterialCardView {
        val card = card()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(column)
        column.addView(sectionTitle("UPNP / SSDP"))
        column.addView(text(
            "Wysyła M-SEARCH UDP na 239.255.255.250:1900 i pokazuje odpowiedzi USN / SERVER / LOCATION.",
            11f,
            colorMuted
        ).apply { setPadding(0, dp(5), 0, dp(8)) })
        ssdpButton = outlinedButton("SZUKAJ URZĄDZEŃ UPnP").apply {
            setOnClickListener { startSsdpDiscovery() }
        }
        column.addView(ssdpButton, LinearLayout.LayoutParams(-1, dp(40)))
        ssdpStatus = text("Gotowe.", 11f, colorMutedDim).apply { setPadding(dp(2), dp(8), 0, 0) }
        column.addView(ssdpStatus)
        ssdpContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ssdpContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return card
    }

    private fun buildResultsSection(root: LinearLayout) {
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(sectionTitle("WYNIKI"), LinearLayout.LayoutParams(0, -2, 1f))
        resultsCountView = text("Hosty: 0 · Porty: 0", 11f, colorMuted)
        headerRow.addView(resultsCountView)
        sortButton = outlinedButton("IP ↑").apply {
            setOnClickListener { toggleSort() }
        }
        headerRow.addView(sortButton, LinearLayout.LayoutParams(dp(62), dp(34)).apply { marginStart = dp(8) })
        root.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportCsvButton = outlinedButton("EKSPORT CSV").apply {
            isEnabled = false
            setOnClickListener { csvExportLauncher.launch("BruceIR_LAN_scan.csv") }
        }
        exportJsonButton = outlinedButton("EKSPORT JSON").apply {
            isEnabled = false
            setOnClickListener { jsonExportLauncher.launch("BruceIR_LAN_scan.json") }
        }
        exportRow.addView(exportCsvButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        exportRow.addView(exportJsonButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(exportRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        searchInput = editTextView("Filtruj: IP, MAC, producent, usługa...")
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                renderResults()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(searchInput, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(10) })

        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsContainer, LinearLayout.LayoutParams(-1, -2))

        emptyView = text(
            "Brak wyników. Uruchom skanowanie, aby wykryć hosty.",
            13f,
            colorMuted
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(20), dp(8), dp(20))
        }
        root.addView(emptyView, LinearLayout.LayoutParams(-1, -2))
    }

    private fun separator(): View = View(this).apply { setBackgroundColor(colorStroke) }

    private fun separatorParams() = LinearLayout.LayoutParams(-1, dp(1)).apply {
        topMargin = dp(14)
        bottomMargin = dp(12)
    }

    private fun sectionTitle(value: String): TextView = text(value, 10.5f, colorAccent, true)

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(colorSurface)
        strokeColor = colorStroke
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun outlinedButton(labelText: String): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = labelText
            textSize = 11f
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(colorAccent)
        }

    private fun segmentedButton(labelText: String): MaterialButton = MaterialButton(this).apply {
        text = labelText
        textSize = 11.5f
        minimumWidth = 0
        cornerRadius = dp(12)
        setPadding(dp(2), 0, dp(2), 0)
        backgroundTintList = ColorStateList.valueOf(colorSurfaceAlt)
        setTextColor(colorMuted)
    }

    private fun editTextView(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        textSize = 13f
        setTextColor(colorText)
        setHintTextColor(0xFF8290A2.toInt())
        setPadding(dp(12), 0, dp(12), 0)
        background = roundedBackground(colorSurfaceAlt, colorStroke, 12)
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun verticalParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun styleToggle(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (selected) colorAccent else colorSurfaceAlt)
        button.setTextColor(if (selected) colorOnAccent else colorMuted)
    }

    // ---------------------------------------------------------------- wybór trybu

    private fun selectMode(mode: TargetMode) {
        targetMode = mode
        modeButtons.forEachIndexed { index, button ->
            styleToggle(button, TargetMode.entries[index] == mode)
        }
        val inputVisible = mode != TargetMode.AUTO
        targetInput.visibility = if (inputVisible) View.VISIBLE else View.GONE
        targetHint.text = when (mode) {
            TargetMode.AUTO -> "Tryb AUTO skanuje wykrytą podsieć /24 (Wi-Fi lub Ethernet)."
            TargetMode.CIDR -> "Format CIDR, np. 192.168.1.0/24 · prefiks 16-30 · maks. 1024 hosty."
            TargetMode.SINGLE -> "Pojedynczy adres IPv4, np. 192.168.1.10."
        }
        if (inputVisible && targetInput.text.isBlank()) {
            targetInput.setText(
                when (mode) {
                    TargetMode.CIDR -> networkContext?.scanCidr.orEmpty()
                    TargetMode.SINGLE -> networkContext?.localIp.orEmpty()
                    TargetMode.AUTO -> ""
                }
            )
        }
    }

    private fun selectProfile(profile: PortProfile) {
        if (profile == PortProfile.CUSTOM && customPortList.isEmpty()) {
            showCustomPortDialog()
            return
        }
        portProfile = profile
        profileButtons.forEachIndexed { index, button ->
            styleToggle(button, PortProfile.entries[index] == profile)
        }
        updateProfileInfo()
    }

    private fun selectTimeout(value: Int) {
        selectedTimeoutMs = value
        timeoutButtons.forEachIndexed { index, button ->
            styleToggle(button, timeoutOptions[index] == value)
        }
    }

    private fun updateProfileInfo() {
        val ports = currentPorts()
        profileInfo.text = "Porty: ${ports.size} · ${summarizePorts(ports)}"
    }

    private fun currentPorts(): List<Int> = when (portProfile) {
        PortProfile.QUICK -> quickPorts
        PortProfile.TOP100 -> topPorts
        PortProfile.CUSTOM -> customPortList
    }

    private fun summarizePorts(ports: List<Int>): String {
        if (ports.isEmpty()) return "brak"
        val head = ports.take(8).joinToString(", ")
        return if (ports.size > 8) "$head…" else head
    }

    private fun showCustomPortDialog() {
        val input = editTextView("np. 1-1024 lub 80,443,8000-8100")
        input.setText(customPortList.joinToString(","))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        column.addView(text("Porty rozdzielone przecinkiem, zakresy w formie a-b. Limit 2000 portów.", 11.5f, colorMuted))
        column.addView(input, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Własny zakres portów")
            .setView(column)
            .setPositiveButton("Zastosuj", null)
            .setNegativeButton("Anuluj", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                runCatching { parsePortExpression(input.text.toString()) }
                    .onSuccess { ports ->
                        customPortList = ports
                        portProfile = PortProfile.CUSTOM
                        profileButtons.forEachIndexed { index, button ->
                            styleToggle(button, PortProfile.entries[index] == PortProfile.CUSTOM)
                        }
                        updateProfileInfo()
                        dialog.dismiss()
                    }
                    .onFailure { error ->
                        input.error = error.message ?: "Nieprawidłowa lista portów."
                    }
            }
        }
        dialog.show()
    }

    private fun parsePortExpression(raw: String): List<Int> {
        val text = raw.trim()
        if (text.isEmpty()) throw ScannerException("Podaj przynajmniej jeden port.")
        val result = sortedSetOf<Int>()
        for (tokenRaw in text.split(",")) {
            val token = tokenRaw.trim()
            if (token.isEmpty()) continue
            val rangeParts = token.split("-")
            when (rangeParts.size) {
                1 -> {
                    val port = token.toIntOrNull() ?: throw ScannerException("Nieprawidłowy port: $token")
                    if (port !in 1..65535) throw ScannerException("Port $port poza zakresem 1-65535.")
                    result += port
                }
                2 -> {
                    val startPart = rangeParts[0].trim()
                    val endPart = rangeParts[1].trim()
                    val start = startPart.toIntOrNull() ?: throw ScannerException("Nieprawidłowy początek zakresu: $startPart")
                    val end = endPart.toIntOrNull() ?: throw ScannerException("Nieprawidłowy koniec zakresu: $endPart")
                    if (start !in 1..65535 || end !in 1..65535) {
                        throw ScannerException("Zakres portów musi mieścić się w 1-65535.")
                    }
                    if (start > end) throw ScannerException("Zakres $start-$end jest odwrócony.")
                    var port = start
                    while (port <= end) {
                        result += port
                        port++
                    }
                }
                else -> throw ScannerException("Nieprawidłowy zapis: $token")
            }
            if (result.size > 2000) throw ScannerException("Limit 2000 portów — wybierz mniejszy zakres.")
        }
        if (result.isEmpty()) throw ScannerException("Nie wybrano żadnego portu.")
        return result.toList()
    }

    private fun showInfoDialog() {
        val message = "Skaner łączy trzy techniki:\n" +
            "1. TCP connect na wybranych portach (bez roota).\n" +
            "2. Odczyt tabeli ARP (/proc/net/arp) — na Androidzie 10+ może być ograniczony, wtedy MAC może być niedostępny.\n" +
            "3. Identyfikacja producenta po OUI adresu MAC i heurystyka typu urządzenia.\n\n" +
            "Skan obejmuje wyłącznie sieci prywatne RFC1918 (10/8, 172.16/12, 192.168/16) w trybie AUTO. " +
            "Limit zakresu CIDR: 1024 hosty. Timeout połączenia: 200-1500 ms. " +
            "UPnP/SSDP korzysta z UDP multicast (M-SEARCH)."
        AlertDialog.Builder(this)
            .setTitle("Jak działa skaner")
            .setMessage(message)
            .setPositiveButton("Rozumiem", null)
            .show()
    }

    // ---------------------------------------------------------------- kontekst sieci

    private fun refreshNetworkContext() {
        networkSummary.text = "Wykrywanie aktywnego interfejsu..."
        networkSummary.setTextColor(colorMuted)
        networkDetail.text = "Wymagany prywatny adres IPv4 (RFC1918) na Wi-Fi lub Ethernet."
        lifecycleScope.launch {
            val detected = withContext(Dispatchers.IO) { runCatching { detectNetworkContext() } }
            detected.onSuccess { context ->
                networkContext = context
                renderNetworkContext(context, null)
            }.onFailure { error ->
                networkContext = null
                renderNetworkContext(null, (error as? ScannerException)?.message)
            }
        }
    }

    private fun detectNetworkContext(): NetworkContext {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw ScannerException("Brak dostępu do ConnectivityManager.")
        val activeNetwork = manager.activeNetwork
            ?: throw ScannerException("Brak aktywnego połączenia sieciowego.")
        val capabilities = manager.getNetworkCapabilities(activeNetwork)
            ?: throw ScannerException("Brak informacji o aktywnym połączeniu.")
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isWifi && !isEthernet) throw ScannerException("Skaner LAN wymaga Wi-Fi lub Ethernetu.")
        val linkProperties = manager.getLinkProperties(activeNetwork)
            ?: throw ScannerException("Brak parametrów adresacji interfejsu.")
        val link = linkProperties.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        } ?: throw ScannerException("Brak adresu IPv4 na interfejsie.")
        val address = link.address as Inet4Address
        if (!isRfc1918(address.address)) {
            throw ScannerException("Wykryty adres nie jest prywatny (RFC1918).")
        }
        val prefix = link.prefixLength
        if (prefix !in 8..30) throw ScannerException("Nieobsługiwany prefiks sieci /$prefix.")
        val scanPrefix = if (prefix < 24) 24 else prefix
        val ipValue = addressToLong(address.address)
        val networkValue = ipValue and prefixMask(scanPrefix)
        val scanCidr = "${longToIp(networkValue)}/$scanPrefix"
        val autoHostCount = ((1L shl (32 - scanPrefix)) - 2).toInt()
        val interfaceName = linkProperties.interfaceName?.takeIf { it.isNotBlank() }
            ?: (if (isWifi) "wlan" else "eth")
        return NetworkContext(
            interfaceName = interfaceName,
            transport = if (isWifi) "Wi-Fi" else "Ethernet",
            localIp = longToIp(ipValue),
            prefixLength = prefix,
            scanCidr = scanCidr,
            autoHostCount = autoHostCount
        )
    }

    private fun renderNetworkContext(context: NetworkContext?, reason: String?) {
        if (context == null) {
            networkSummary.text = "Brak aktywnego Wi-Fi / Ethernet"
            networkSummary.setTextColor(colorDanger)
            networkDetail.text = reason ?: "Podłącz się do sieci prywatnej, aby użyć trybu AUTO."
            return
        }
        networkSummary.text = "${context.interfaceName} · ${context.transport} · ${context.localIp}/${context.prefixLength}"
        networkSummary.setTextColor(colorAccentBlue)
        networkDetail.text = "Zakres AUTO: ${context.scanCidr} · ${context.autoHostCount} hostów · prefiks /${context.prefixLength}"
        if (targetInput.text.isBlank()) {
            targetInput.setText(
                when (targetMode) {
                    TargetMode.CIDR -> context.scanCidr
                    TargetMode.SINGLE -> context.localIp
                    TargetMode.AUTO -> ""
                }
            )
        }
    }

    // ---------------------------------------------------------------- cel skanowania

    private fun resolveTargetBlocking(mode: TargetMode, input: String): Pair<ScanTarget, NetworkContext?> =
        when (mode) {
            TargetMode.AUTO -> {
                val context = detectNetworkContext()
                val spec = parseCidrSpec(context.scanCidr)
                ScanTarget(context.scanCidr, spec.hosts) to context
            }
            TargetMode.CIDR -> {
                val spec = parseCidrSpec(input)
                ScanTarget("${longToIp(spec.networkValue)}/${spec.prefix}", spec.hosts) to null
            }
            TargetMode.SINGLE -> {
                val ip = parseSingleIp(input)
                ScanTarget(ip, listOf(ip)) to null
            }
        }

    private fun parseSingleIp(raw: String): String {
        val value = parseIpv4(raw) ?: throw ScannerException("Nieprawidłowy adres IPv4: ${raw.trim()}")
        return longToIp(value)
    }

    private fun parseCidrSpec(raw: String): CidrSpec {
        val input = raw.trim()
        if (input.isEmpty()) throw ScannerException("Podaj zakres CIDR, np. 192.168.1.0/24.")
        val parts = input.split("/")
        if (parts.size != 2) throw ScannerException("Format CIDR: adres/prefiks, np. 192.168.1.0/24.")
        val baseIp = parts[0].trim()
        val prefix = parts[1].trim().toIntOrNull()
            ?: throw ScannerException("Prefiks musi być liczbą.")
        if (prefix !in 16..30) throw ScannerException("Prefiks musi być w zakresie 16-30.")
        val baseValue = parseIpv4(baseIp) ?: throw ScannerException("Nieprawidłowy adres IPv4: $baseIp")
        val networkValue = baseValue and prefixMask(prefix)
        val hostCount = (1L shl (32 - prefix)) - 2
        if (hostCount > 1024) {
            throw ScannerException("Zakres /$prefix ma $hostCount hostów (limit 1024). Użyj /22 lub mniejszego.")
        }
        if (hostCount < 1) throw ScannerException("Zakres /$prefix nie zawiera hostów.")
        val hosts = (1L..hostCount).map { offset -> longToIp(networkValue + offset) }
        return CidrSpec(networkValue, prefix, hosts)
    }

    private fun parseIpv4(raw: String): Long? {
        val parts = raw.trim().split(".")
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            value = (value shl 8) or number.toLong()
        }
        return value
    }

    private fun addressToLong(bytes: ByteArray): Long {
        if (bytes.size < 4) return 0L
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return value
    }

    private fun longToIp(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }

    private fun prefixMask(prefix: Int): Long = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    private fun ipToLong(ip: String): Long = parseIpv4(ip) ?: 0L

    private fun isRfc1918(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    // ---------------------------------------------------------------- skan

    private fun toggleScan() {
        val active = scanJob
        if (active != null) {
            active.cancel()
            statusView.text = "Zatrzymywanie skanowania..."
            return
        }
        startFullScan()
    }

    private fun startFullScan() {
        if (scanJob != null) return
        val ports = currentPorts()
        if (ports.isEmpty()) {
            statusView.text = "Brak portów w wybranym profilu."
            return
        }
        val mode = targetMode
        val input = targetInput.text.toString()
        val timeout = selectedTimeoutMs
        resetScanState()
        setScanning(true)
        statusView.text = "Rozpoznawanie interfejsu sieciowego..."
        scanStartedAt.set(SystemClock.elapsedRealtime())
        scanUnitLabel = "hostów"
        startElapsedTicker()
        scanJob = lifecycleScope.launch {
            try {
                performFullScan(mode, input, ports, timeout)
            } catch (error: ScannerException) {
                statusView.text = error.message ?: "Nie udało się przygotować skanowania."
                showScanFailureState()
            } catch (_: CancellationException) {
                renderPartialResults()
                statusView.text = "Skanowanie zatrzymane · ${results.size} hostów"
            } catch (_: Exception) {
                statusView.text = "Skanowanie przerwane przez błąd."
                showScanFailureState()
            } finally {
                setScanning(false)
            }
        }
    }

    private suspend fun performFullScan(mode: TargetMode, input: String, ports: List<Int>, timeoutMs: Int) {
        val resolved = withContext(Dispatchers.IO) { resolveTargetBlocking(mode, input) }
        val target = resolved.first
        val context = resolved.second
        if (context != null) {
            networkContext = context
            renderNetworkContext(context, null)
        }
        scannedSubnetLabel = target.label
        activeTargetHosts = target.hosts
        progressBar.max = target.hosts.size
        progressBar.progress = 0
        statusView.text = "Skanowanie ${target.label}: 0 / ${target.hosts.size} hostów"
        scanTargetHosts(target.hosts, ports, timeoutMs, activeDiscovery)
        val arp = withContext(Dispatchers.IO) { readArpTable() }
        results = buildHostResults(target.hosts, activeDiscovery, arp)
        renderResults()
        val elapsed = SystemClock.elapsedRealtime() - scanStartedAt.get()
        val openTotal = results.sumOf { it.openPorts.size }
        statusView.text = "Zakończono skan ${target.label}: ${results.size} hostów, $openTotal otwartych portów · ${formatDuration(elapsed)}"
        elapsedView.text = buildStats(target.hosts.size, target.hosts.size, openTotal, elapsed)
        progressBar.max = target.hosts.size
        progressBar.progress = target.hosts.size
    }

    private suspend fun scanTargetHosts(
        hosts: List<String>,
        ports: List<Int>,
        timeoutMs: Int,
        sink: ConcurrentHashMap<String, MutableList<OpenPort>>
    ) {
        val total = hosts.size
        val semaphore = Semaphore(64)
        val progressStep = (total / 100).coerceAtLeast(1)
        coroutineScope {
            hosts.map { ip ->
                async(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val open = probeHost(ip, ports, timeoutMs, semaphore)
                    if (open.isNotEmpty()) sink[ip] = open.toMutableList()
                    val done = scannedHosts.incrementAndGet()
                    val openCount = openPortsFound.addAndGet(open.size)
                    if (done == total || done % progressStep == 0) {
                        publishProgress(done, total, openCount, done == total) {
                            "Skanowanie $scannedSubnetLabel: $done / $total hostów"
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun probeHost(
        ip: String,
        ports: List<Int>,
        timeoutMs: Int,
        semaphore: Semaphore
    ): List<OpenPort> {
        val openPorts = mutableListOf<OpenPort>()
        var timeoutsInRow = 0
        for (port in ports) {
            currentCoroutineContext().ensureActive()
            val outcome = semaphore.withPermit { probePort(ip, port, timeoutMs) }
            val openPort = outcome.openPort
            if (openPort != null) {
                openPorts += openPort
                timeoutsInRow = 0
            } else if (outcome.timedOut) {
                timeoutsInRow++
                if (timeoutsInRow >= 4 && openPorts.isEmpty()) break
            } else {
                timeoutsInRow = 0
            }
        }
        return openPorts
    }

    private suspend fun probeSingleHostProgress(ip: String, ports: List<Int>, timeoutMs: Int): List<OpenPort> {
        val openPorts = mutableListOf<OpenPort>()
        val total = ports.size
        val semaphore = Semaphore(64)
        var timeoutsInRow = 0
        for ((index, port) in ports.withIndex()) {
            currentCoroutineContext().ensureActive()
            val outcome = semaphore.withPermit { probePort(ip, port, timeoutMs) }
            val openPort = outcome.openPort
            if (openPort != null) {
                openPorts += openPort
                timeoutsInRow = 0
            } else if (outcome.timedOut) {
                timeoutsInRow++
                if (timeoutsInRow >= 24 && openPorts.isEmpty()) break
            } else {
                timeoutsInRow = 0
            }
            val done = index + 1
            scannedHosts.set(done)
            openPortsFound.set(openPorts.size)
            if (done == total || done % 32 == 0) {
                val openCount = openPorts.size
                publishProgress(done, total, openCount, done == total) {
                    "Skanowanie $ip: $done / $total portów · otwarte: $openCount"
                }
            }
        }
        return openPorts.sortedBy { it.port }
    }

    private fun startHostScan(ip: String, deep: Boolean) {
        if (scanJob != null) {
            statusView.text = "Poczekaj na zakończenie bieżącego skanu."
            return
        }
        val ports = if (deep) (1..1024).toList() else currentPorts()
        if (ports.isEmpty()) {
            statusView.text = "Brak portów do skanowania tego hosta."
            return
        }
        val timeout = selectedTimeoutMs
        prepareProgress()
        setScanning(true)
        scanStartedAt.set(SystemClock.elapsedRealtime())
        scannedSubnetLabel = ip
        activeTargetHosts = listOf(ip)
        scanUnitLabel = "portów"
        progressBar.max = ports.size
        progressBar.progress = 0
        statusView.text = "Skanowanie $ip: 0 / ${ports.size} portów"
        startElapsedTicker()
        scanJob = lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { probeSingleHostProgress(ip, ports, timeout) }
                val arp = withContext(Dispatchers.IO) { readArpTable() }
                val updatedHost = buildHostResults(listOf(ip), mapOf(ip to found), arp).firstOrNull()
                val updated = results.filterNot { it.ip == ip }.toMutableList()
                if (updatedHost != null) updated += updatedHost
                results = updated.sortedBy { ipToLong(it.ip) }
                renderResults()
                statusView.text = if (updatedHost == null) {
                    "Host $ip nie odpowiedział na żadnym porcie (${ports.size} sprawdzonych)."
                } else {
                    "Skan hosta $ip zakończony: ${updatedHost.openPorts.size} otwartych portów."
                }
                progressBar.max = ports.size
                progressBar.progress = ports.size
            } catch (_: CancellationException) {
                statusView.text = "Skanowanie hosta zatrzymane."
            } catch (_: Exception) {
                statusView.text = "Skanowanie hosta nie powiodło się."
                showScanFailureState()
            } finally {
                setScanning(false)
            }
        }
    }

    private suspend fun renderPartialResults() {
        val arp = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { readArpTable() }.getOrDefault(emptyMap())
        }
        results = buildHostResults(activeTargetHosts, activeDiscovery, arp)
        renderResults()
    }

    private fun resetScanState() {
        results = emptyList()
        resultsContainer.removeAllViews()
        exportCsvButton.isEnabled = false
        exportJsonButton.isEnabled = false
        resultsCountView.text = "Hosty: 0 · Porty: 0"
        emptyView.visibility = View.VISIBLE
        emptyView.text = "Skanowanie w toku..."
        prepareProgress()
    }

    private fun prepareProgress() {
        activeDiscovery.clear()
        activeTargetHosts = emptyList()
        scannedHosts.set(0)
        openPortsFound.set(0)
        lastProgressPublish.set(0)
        progressBar.max = 1
        progressBar.progress = 0
        elapsedView.text = ""
    }

    private fun showScanFailureState() {
        if (!::emptyView.isInitialized || results.isNotEmpty()) return
        emptyView.text = "Skanowanie nie zostało uruchomione. Sprawdź cel skanowania i połączenie sieciowe."
        emptyView.visibility = View.VISIBLE
    }

    private fun setScanning(scanning: Boolean) {
        if (::scanButton.isInitialized) {
            scanButton.text = if (scanning) "ZATRZYMAJ SKAN" else "ROZPOCZNIJ SKAN"
            scanButton.backgroundTintList = ColorStateList.valueOf(if (scanning) colorDanger else colorAccent)
            scanButton.setTextColor(if (scanning) colorText else colorOnAccent)
        }
        modeButtons.forEach { it.isEnabled = !scanning }
        profileButtons.forEach { it.isEnabled = !scanning }
        timeoutButtons.forEach { it.isEnabled = !scanning }
        if (::targetInput.isInitialized) targetInput.isEnabled = !scanning
        if (!scanning) {
            elapsedJob?.cancel()
            elapsedJob = null
            scanJob = null
        }
    }

    private suspend fun publishProgress(
        done: Int,
        total: Int,
        openCount: Int,
        force: Boolean,
        statusProvider: () -> String
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = lastProgressPublish.get()
        if (!force && now - last < 100L) return
        if (!lastProgressPublish.compareAndSet(last, now)) return
        withContext(Dispatchers.Main) {
            progressBar.max = total
            progressBar.progress = done
            statusView.text = statusProvider()
            elapsedView.text = buildStats(done, total, openCount, now - scanStartedAt.get())
        }
    }

    private fun startElapsedTicker() {
        elapsedJob?.cancel()
        val startedAt = scanStartedAt.get()
        elapsedJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val total = if (::progressBar.isInitialized) progressBar.max.coerceAtLeast(1) else 1
                if (::elapsedView.isInitialized) {
                    elapsedView.text = buildStats(scannedHosts.get(), total, openPortsFound.get(), elapsed)
                }
                delay(500)
            }
        }
    }

    private fun buildStats(done: Int, total: Int, openCount: Int, elapsedMs: Long): String {
        val seconds = elapsedMs / 1000.0
        val rate = if (seconds > 0.0) done / seconds else 0.0
        val rateText = String.format(Locale.US, "%.1f", rate)
        return "$scanUnitLabel: $done/$total · porty: $openCount · $rateText $scanUnitLabel/s · ${formatDuration(elapsedMs)}"
    }

    private fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "$minutes min $seconds s" else "$seconds s"
    }

    // ---------------------------------------------------------------- sondowanie portów

    private fun probePort(ip: String, port: Int, timeoutMs: Int): ProbeOutcome {
        val started = System.nanoTime()
        var timedOut = false
        val outcome = runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val latency = (System.nanoTime() - started) / 1_000_000L
                val fingerprint = runCatching {
                    socket.soTimeout = 1200
                    fingerprintPort(socket, ip, port)
                }.getOrDefault(FingerprintInfo(null, false))
                OpenPort(
                    port = port,
                    service = serviceNames[port] ?: "TCP",
                    latencyMs = latency,
                    banner = fingerprint.banner,
                    tls = fingerprint.tls
                )
            }
        }.onFailure { error ->
            if (error is SocketTimeoutException ||
                (error is SocketException && error.message?.contains("timed out", ignoreCase = true) == true)
            ) {
                timedOut = true
            }
        }
        return ProbeOutcome(outcome.getOrNull(), timedOut)
    }

    private fun fingerprintPort(socket: Socket, ip: String, port: Int): FingerprintInfo {
        var banner: String? = null
        var tls = false
        when {
            port in httpPorts -> banner = httpBanner(socket, ip)
            port in tlsPorts -> {
                tls = attemptTls(ip, port)
                banner = if (tls) "TLS/SSL" else null
            }
            port in greetingPorts -> banner = readGreetingLine(socket)
            port in rtspPorts -> banner = rtspBanner(socket, ip, port)
            port == 445 -> banner = "SMB"
            port == 9100 -> banner = "RAW print"
            port == 1883 -> banner = "MQTT"
        }
        val cleanBanner = banner?.let(::sanitizeBanner)?.takeIf { it.isNotEmpty() }
        return FingerprintInfo(cleanBanner, tls)
    }

    private fun httpBanner(socket: Socket, ip: String): String? {
        var firstLine: String? = null
        var serverHeader: String? = null
        runCatching {
            val request = "GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: BruceIR-NetScanner/2.0\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            firstLine = reader.readLine()
            for (index in 0 until 24) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Server:", ignoreCase = true)) {
                    serverHeader = line.substringAfter(':').trim()
                    break
                }
            }
        }
        val parts = mutableListOf<String>()
        firstLine?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it.take(90) }
        serverHeader?.takeIf { it.isNotEmpty() }?.let { parts += "Server: ${it.take(60)}" }
        return parts.joinToString(" · ").ifBlank { null }
    }

    private fun readGreetingLine(socket: Socket): String? = runCatching {
        socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()?.trim()?.take(120)
    }.getOrNull()

    private fun rtspBanner(socket: Socket, ip: String, port: Int): String? = runCatching {
        val request = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: BruceIR\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()?.trim()?.take(100)
    }.getOrNull()

    private fun attemptTls(ip: String, port: Int): Boolean = runCatching {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        factory.createSocket(ip, port).use { rawSocket ->
            val sslSocket = rawSocket as? SSLSocket ?: return@runCatching false
            sslSocket.soTimeout = 1200
            sslSocket.startHandshake()
            true
        }
    }.getOrDefault(false)

    private fun sanitizeBanner(value: String): String {
        val cleaned = value.map { character ->
            val code = character.code
            if (code in 0..31 || code == 127 || code > 126) ' ' else character
        }.joinToString("")
        return cleaned.replace(Regex("\\s+"), " ").trim().take(110)
    }

    // ---------------------------------------------------------------- ARP / OUI / typ urządzenia

    private fun readArpTable(): Map<String, String> = runCatching {
        val file = File("/proc/net/arp")
        if (!file.exists() || !file.canRead()) return@runCatching emptyMap()
        val table = LinkedHashMap<String, String>()
        file.useLines(Charsets.ISO_8859_1) { lines ->
            lines.drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 4) return@forEach
                val ip = parts[0]
                val mac = parts[3].uppercase(Locale.US)
                if (parseIpv4(ip) == null) return@forEach
                if (!isValidMac(mac)) return@forEach
                table[ip] = mac
            }
        }
        table
    }.getOrDefault(emptyMap())

    private fun isValidMac(mac: String): Boolean {
        if (mac == "00:00:00:00:00:00" || mac == "02:00:00:00:00:00") return false
        val parts = mac.split(":")
        if (parts.size != 6) return false
        return parts.all { part -> part.length == 2 && part.all { it.isDigit() || it in 'A'..'F' } }
    }

    private fun buildHostResults(
        targetHosts: List<String>,
        found: Map<String, List<OpenPort>>,
        arp: Map<String, String>
    ): List<HostResult> {
        val built = mutableListOf<HostResult>()
        for (ip in targetHosts) {
            val ports = found[ip].orEmpty().sortedBy { it.port }
            val mac = arp[ip]
            if (ports.isEmpty() && mac == null) continue
            val vendor = mac?.let(::vendorForMac)
            val latencies = ports.map { it.latencyMs }
            val average = if (latencies.isEmpty()) 0L else latencies.sum().toDouble().div(latencies.size).roundToLong()
            built += HostResult(
                ip = ip,
                mac = mac,
                vendor = vendor,
                deviceType = inferDeviceType(ip, ports, vendor),
                openPorts = ports,
                hasArpEntry = mac != null,
                avgLatencyMs = average
            )
        }
        return built.sortedBy { ipToLong(it.ip) }
    }

    private fun vendorForMac(mac: String): String? {
        val normalized = mac.trim().uppercase(Locale.US).replace('-', ':')
        val parts = normalized.split(":")
        if (parts.size < 3) return null
        return ouiVendors[parts.take(3).joinToString(":")]
    }

    private fun inferDeviceType(ip: String, ports: List<OpenPort>, vendor: String?): String {
        val openPorts = ports.map { it.port }.toSet()
        val vendorName = vendor.orEmpty()
        return when {
            openPorts.contains(554) || openPorts.contains(8554) || openPorts.contains(1935) -> "Kamera / NVR"
            openPorts.contains(9100) || openPorts.contains(631) || openPorts.contains(515) -> "Drukarka"
            openPorts.contains(445) || openPorts.contains(5000) || openPorts.contains(5001) ||
                openPorts.contains(2049) -> "NAS / serwer plików"
            ip.endsWith(".1") && (openPorts.contains(80) || openPorts.contains(443) || openPorts.contains(8443)) ->
                "Router / brama"
            openPorts.contains(3389) -> "Windows (RDP)"
            openPorts.contains(32400) -> "Serwer Plex"
            openPorts.contains(8123) -> "Home Assistant"
            openPorts.contains(5555) -> "Android / ADB"
            openPorts.contains(1883) || openPorts.contains(8883) -> "IoT (MQTT)"
            openPorts.contains(22) && (openPorts.contains(80) || openPorts.contains(443)) -> "Serwer / Linux"
            openPorts.contains(53) -> "DNS / serwer sieciowy"
            vendorName.contains("Apple", ignoreCase = true) -> "Urządzenie Apple"
            vendorName.contains("Samsung", ignoreCase = true) -> "Urządzenie Samsung"
            vendorName.contains("Xiaomi", ignoreCase = true) -> "Urządzenie Xiaomi"
            vendorName.contains("Raspberry", ignoreCase = true) -> "Raspberry Pi"
            vendorName.contains("Espressif", ignoreCase = true) -> "IoT (Espressif)"
            vendorName.contains("Sonoff", ignoreCase = true) -> "Przekaźnik Sonoff"
            vendorName.contains("Philips", ignoreCase = true) -> "Oświetlenie Hue"
            vendorName.contains("Sonos", ignoreCase = true) -> "Głośnik Sonos"
            vendorName.contains("Amazon", ignoreCase = true) -> "Echo / Amazon"
            vendorName.contains("Google", ignoreCase = true) -> "Chromecast / Google"
            vendorName.contains("Hikvision", ignoreCase = true) ||
                vendorName.contains("Dahua", ignoreCase = true) -> "Kamera / NVR"
            vendorName.contains("Synology", ignoreCase = true) ||
                vendorName.contains("QNAP", ignoreCase = true) ||
                vendorName.contains("Western Digital", ignoreCase = true) -> "NAS / serwer plików"
            vendorName.contains("Brother", ignoreCase = true) ||
                vendorName.contains("Hewlett", ignoreCase = true) -> "Drukarka"
            vendorName.contains("AVM", ignoreCase = true) -> "Fritz!Box / router"
            vendorName.contains("Nintendo", ignoreCase = true) -> "Konsola Nintendo"
            vendorName.contains("Sony", ignoreCase = true) -> "Urządzenie Sony"
            vendorName.contains("NVIDIA", ignoreCase = true) -> "PC / NVIDIA"
            vendorName.contains("Intel", ignoreCase = true) -> "PC / laptop (Intel)"
            vendorName.contains("Broadcom", ignoreCase = true) -> "Host z kartą Broadcom"
            vendorName.contains("MikroTik", ignoreCase = true) ||
                vendorName.contains("Ubiquiti", ignoreCase = true) ||
                vendorName.contains("TP-Link", ignoreCase = true) ||
                vendorName.contains("Netgear", ignoreCase = true) ||
                vendorName.contains("D-Link", ignoreCase = true) ||
                vendorName.contains("ASUS", ignoreCase = true) ||
                vendorName.contains("Cisco", ignoreCase = true) -> "Sprzęt sieciowy"
            vendorName.isNotEmpty() -> "Host LAN · $vendorName"
            else -> if (ports.isNotEmpty()) "Host sieci LAN" else "Host w tabeli ARP"
        }
    }

    // ---------------------------------------------------------------- wyniki

    private fun renderResults() {
        if (!::resultsContainer.isInitialized) return
        resultsContainer.removeAllViews()
        val filtered = filteredSortedResults()
        val totalPorts = results.sumOf { it.openPorts.size }
        resultsCountView.text = "Hosty: ${results.size} · Porty: $totalPorts"
        exportCsvButton.isEnabled = results.isNotEmpty()
        exportJsonButton.isEnabled = results.isNotEmpty()
        when {
            results.isEmpty() -> {
                emptyView.text = "Brak wyników. Uruchom skanowanie, aby wykryć hosty."
                emptyView.visibility = View.VISIBLE
            }
            filtered.isEmpty() -> {
                emptyView.text = "Brak hostów pasujących do filtra \"${searchQuery.trim()}\"."
                emptyView.visibility = View.VISIBLE
            }
            else -> emptyView.visibility = View.GONE
        }
        filtered.forEach { host ->
            resultsContainer.addView(buildHostCard(host), verticalParams(bottom = 8))
        }
    }

    private fun filteredSortedResults(): List<HostResult> {
        val needle = searchQuery.trim().lowercase(Locale.US)
        val filtered = if (needle.isEmpty()) {
            results
        } else {
            results.filter { host ->
                host.ip.contains(needle) ||
                    host.mac?.lowercase(Locale.US)?.contains(needle) == true ||
                    host.vendor?.lowercase(Locale.US)?.contains(needle) == true ||
                    host.deviceType.lowercase(Locale.US).contains(needle) ||
                    host.openPorts.any { port ->
                        port.service.lowercase(Locale.US).contains(needle) ||
                            port.port.toString() == needle ||
                            port.banner?.lowercase(Locale.US)?.contains(needle) == true
                    }
            }
        }
        return if (sortAscending) filtered.sortedBy { ipToLong(it.ip) } else filtered.sortedByDescending { ipToLong(it.ip) }
    }

    private fun toggleSort() {
        sortAscending = !sortAscending
        sortButton.text = if (sortAscending) "IP ↑" else "IP ↓"
        renderResults()
    }

    private fun isWebPort(port: OpenPort): Boolean {
        val p = port.port
        if (port.tls || p == 443 || p == 8443 || p == 4443 || p == 9443) return true
        if (p == 80 || p == 81 || p == 82 || p == 83 || p == 84 || p == 85 || p == 8080 || p == 8000 || p == 8081 || p == 8088 || p == 8090 || p == 8123 || p == 5000 || p == 3000 || p == 9000 || p == 32400) return true
        val svc = port.service.lowercase(Locale.US)
        return svc.contains("http") || svc.contains("web") || svc.contains("homeassistant") || svc.contains("plex")
    }

    private fun getWebUrl(ip: String, port: OpenPort): String {
        val scheme = if (port.tls || port.port == 443 || port.port == 8443 || port.port == 9443) "https" else "http"
        val portStr = if ((scheme == "http" && port.port == 80) || (scheme == "https" && port.port == 443)) "" else ":${port.port}"
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

    private fun buildHostCard(host: HostResult): MaterialCardView {
        val card = card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { showHostDetails(host) }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(text(host.ip, 16f, colorText, true))
        val identityParts = mutableListOf<String>()
        identityParts += host.mac ?: "brak MAC (ARP)"
        host.vendor?.let { identityParts += it }
        identity.addView(text(identityParts.joinToString(" · "), 11.5f, colorMuted).apply {
            setPadding(0, dp(3), 0, 0)
        })
        topRow.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(text(host.deviceType, 10.5f, colorAccent, true))
        column.addView(topRow)

        if (host.openPorts.isEmpty()) {
            column.addView(text(
                "Brak otwartych portów · host wykryty przez tabelę ARP",
                11.5f,
                colorMutedDim
            ).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            val webPorts = host.openPorts.filter { isWebPort(it) }

            host.openPorts.forEach { port ->
                val line = buildString {
                    append(port.port)
                    append('/')
                    append(port.service)
                    append(" (")
                    append(port.latencyMs)
                    append(" ms)")
                    if (port.tls) append(" · TLS")
                    port.banner?.let { banner ->
                        append(" · ")
                        append(banner.take(70))
                    }
                }
                column.addView(text(line, 11.5f, colorAccentBlue).apply {
                    setPadding(0, dp(5), 0, 0)
                })
            }

            if (webPorts.isNotEmpty()) {
                val webButtonsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                }
                webPorts.take(3).forEach { port ->
                    val url = getWebUrl(host.ip, port)
                    val btnWeb = MaterialButton(this).apply {
                        text = "🌐 ${port.port}"
                        textSize = 10f
                        setPadding(dp(6), 0, dp(6), 0)
                        setBackgroundColor(0xFF43C59E.toInt())
                        setTextColor(Color.BLACK)
                        setOnClickListener { openUrlOptions(url) }
                    }
                    webButtonsRow.addView(btnWeb, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
                }
                column.addView(webButtonsRow)
            }

            val services = host.openPorts.map { it.service }.distinct().joinToString(", ")
            column.addView(text(
                "Śr. opóźnienie TCP: ${host.avgLatencyMs} ms · usługi: $services",
                10.5f,
                colorMutedDim
            ).apply { setPadding(0, dp(8), 0, 0) })
        }
        card.addView(column)
        return card
    }

    private fun showHostDetails(host: HostResult) {
        val details = buildString {
            appendLine("IP: ${host.ip}")
            appendLine("MAC: ${host.mac ?: "—"}")
            appendLine("Producent: ${host.vendor ?: "nieznany"}")
            appendLine("Typ urządzenia: ${host.deviceType}")
            appendLine("Wpis ARP: ${if (host.hasArpEntry) "obecny" else "brak"}")
            appendLine("Śr. opóźnienie TCP: ${host.avgLatencyMs} ms")
            appendLine("Otwarte porty: ${host.openPorts.size}")
            host.openPorts.forEach { port ->
                append("  ${port.port}/${port.service} · ${port.latencyMs} ms")
                if (port.tls) append(" · TLS")
                if (isWebPort(port)) append("  [WEB: ${getWebUrl(host.ip, port)}]")
                appendLine()
                port.banner?.let { banner -> appendLine("    $banner") }
            }
        }

        val webPorts = host.openPorts.filter { isWebPort(it) }

        val builder = AlertDialog.Builder(this)
            .setTitle("Szczegóły hosta (${host.ip})")
            .setMessage(details.trim())
            .setPositiveButton("Głęboki skan 1-1024") { _, _ -> startHostScan(host.ip, deep = true) }
            .setNegativeButton("Skanuj ponownie") { _, _ -> startHostScan(host.ip, deep = false) }

        if (webPorts.isNotEmpty()) {
            val firstUrl = getWebUrl(host.ip, webPorts.first())
            builder.setNeutralButton("🌐 Otwórz Web ($firstUrl)") { _, _ -> openUrlOptions(firstUrl) }
        } else {
            builder.setNeutralButton("Zamknij", null)
        }
        builder.show()
    }

    // ---------------------------------------------------------------- eksport

    private fun exportResults(uri: Uri, json: Boolean) {
        val snapshot = results
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "Brak wyników do eksportu.", Toast.LENGTH_SHORT).show()
            return
        }
        val label = if (json) "JSON" else "CSV"
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = if (json) buildJsonExport(snapshot) else buildCsvExport(snapshot)
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(payload)
                    } ?: throw IllegalStateException("Nie można otworzyć pliku wyjściowego.")
                }
            }
            outcome.onSuccess {
                Toast.makeText(
                    this@AdvancedNetworkScannerActivity,
                    "Zapisano $label: ${snapshot.size} hostów.",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@AdvancedNetworkScannerActivity,
                    "Eksport $label nie powiódł się.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildCsvExport(hosts: List<HostResult>): String = buildString {
        appendLine("ip,mac,vendor,device_type,arp_entry,avg_latency_ms,open_ports,services,latencies_ms")
        hosts.forEach { host ->
            val fields = listOf(
                host.ip,
                host.mac.orEmpty(),
                host.vendor.orEmpty(),
                host.deviceType,
                host.hasArpEntry.toString(),
                host.avgLatencyMs.toString(),
                host.openPorts.joinToString(";") { it.port.toString() },
                host.openPorts.joinToString(";") { it.service },
                host.openPorts.joinToString(";") { it.latencyMs.toString() }
            )
            appendLine(fields.joinToString(",") { csv(it) })
        }
    }

    private fun buildJsonExport(hosts: List<HostResult>): String {
        val root = JsonObject()
        root.addProperty("scanner", "BruceIR AdvancedNetworkScanner")
        root.addProperty("generated_at_epoch_ms", System.currentTimeMillis())
        root.addProperty("target", scannedSubnetLabel)
        root.addProperty("hosts_count", hosts.size)
        val hostsArray = JsonArray()
        hosts.forEach { host ->
            val item = JsonObject()
            item.addProperty("ip", host.ip)
            item.addProperty("mac", host.mac)
            item.addProperty("vendor", host.vendor)
            item.addProperty("device_type", host.deviceType)
            item.addProperty("arp_entry", host.hasArpEntry)
            item.addProperty("avg_latency_ms", host.avgLatencyMs)
            val portsArray = JsonArray()
            host.openPorts.forEach { port ->
                val portObject = JsonObject()
                portObject.addProperty("port", port.port)
                portObject.addProperty("service", port.service)
                portObject.addProperty("latency_ms", port.latencyMs)
                portObject.addProperty("tls", port.tls)
                portObject.addProperty("banner", port.banner)
                portsArray.add(portObject)
            }
            item.add("ports", portsArray)
            hostsArray.add(item)
        }
        root.add("hosts", hostsArray)
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    // ---------------------------------------------------------------- SSDP / UPnP

    private fun startSsdpDiscovery() {
        val active = ssdpJob
        if (active != null) {
            active.cancel()
            ssdpStatus.text = "Zatrzymywanie wyszukiwania UPnP..."
            return
        }
        ssdpButton.text = "ZATRZYMAJ WYSZUKIWANIE"
        ssdpStatus.text = "Wysyłanie M-SEARCH na 239.255.255.250:1900..."
        ssdpContainer.removeAllViews()
        ssdpJob = lifecycleScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) { runSsdpDiscovery() }
                renderSsdpDevices(devices)
                ssdpStatus.text = if (devices.isEmpty()) {
                    "Nie znaleziono urządzeń UPnP/SSDP."
                } else {
                    "Znaleziono ${devices.size} urządzeń UPnP/SSDP."
                }
            } catch (_: CancellationException) {
                ssdpStatus.text = "Wyszukiwanie UPnP zatrzymane."
            } catch (_: Exception) {
                ssdpStatus.text = "Wyszukiwanie UPnP nie powiodło się."
            } finally {
                ssdpButton.text = "SZUKAJ URZĄDZEŃ UPnP"
                ssdpJob = null
            }
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        manager?.createMulticastLock("bruceir_ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private suspend fun runSsdpDiscovery(): List<SsdpDevice> = withContext(Dispatchers.IO) {
        val multicastLock = acquireMulticastLock()
        try {
            val devices = LinkedHashMap<String, SsdpDevice>()
            DatagramSocket().use { socket ->
                runCatching { socket.broadcast = true }
                socket.soTimeout = 400
                val message = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n" +
                    "USER-AGENT: Android/UPnP/1.1 BruceIR\r\n\r\n"
                val payload = message.toByteArray(Charsets.ISO_8859_1)
                val target = InetAddress.getByName("239.255.255.250")
                runCatching { socket.send(DatagramPacket(payload, payload.size, target, 1900)) }
                val deadline = System.currentTimeMillis() + 2600
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val incoming = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(incoming)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val response = String(incoming.data, incoming.offset, incoming.length, Charsets.ISO_8859_1)
                    val headers = parseHttpHeaders(response)
                    val location = headers["location"].orEmpty()
                    val usn = headers["usn"].orEmpty()
                    val server = headers["server"].orEmpty()
                    val searchTarget = headers["st"].orEmpty()
                    val address = incoming.address?.hostAddress.orEmpty()
                    val key = location.ifBlank { usn.ifBlank { "$address|$searchTarget" } }
                    if (!devices.containsKey(key)) {
                        devices[key] = SsdpDevice(
                            address = address,
                            server = server.take(120),
                            location = location.take(160),
                            usn = usn.take(120),
                            searchTarget = searchTarget.take(80)
                        )
                    }
                }
            }
            devices.values.sortedWith(compareBy<SsdpDevice> { ipToLong(it.address) }.thenBy { it.location })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    private fun parseHttpHeaders(response: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        response.lineSequence().drop(1).forEach { rawLine ->
            val line = rawLine.trim()
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@forEach
            val name = line.substring(0, separatorIndex).trim().lowercase(Locale.US)
            val value = line.substring(separatorIndex + 1).trim()
            if (value.isNotEmpty() && !headers.containsKey(name)) headers[name] = value
        }
        return headers
    }

    private fun renderSsdpDevices(devices: List<SsdpDevice>) {
        ssdpContainer.removeAllViews()
        if (devices.isEmpty()) return
        devices.forEach { device ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground(colorSurfaceAlt, colorStroke, 10)
            }
            column.addView(text(device.address.ifBlank { "nieznany adres" }, 12f, colorText, true))
            if (device.server.isNotBlank()) {
                column.addView(text("SERVER: ${device.server}", 10.5f, colorAccentBlue))
            }
            if (device.usn.isNotBlank()) {
                column.addView(text("USN: ${device.usn}", 10.5f, colorMuted))
            }
            if (device.location.isNotBlank()) {
                column.addView(text("LOCATION: ${device.location}", 10.5f, colorMutedDim))
            }
            if (device.searchTarget.isNotBlank()) {
                column.addView(text("ST: ${device.searchTarget}", 10.5f, colorMutedDim))
            }
            ssdpContainer.addView(column, verticalParams(bottom = 6))
        }
    }
}
