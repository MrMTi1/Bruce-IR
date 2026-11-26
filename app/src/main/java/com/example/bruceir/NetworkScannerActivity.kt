package com.example.bruceir

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket

/**
 * Owen-Nmap module - Nmap-like network scanner with service detection and banner grabbing
 * Features:
 * - Port scanning (TCP scan)
 * - Service detection (SSH, HTTP, HTTPS)
 * - Banner grabbing
 * - Aggressive and passive scanning modes
 */
class NetworkScannerActivity : AppCompatActivity() {

    private lateinit var etTargetHost: EditText
    private lateinit var etTargetPort: EditText
    private lateinit var btnScan: Button
    private lateinit var tvResults: TextView
    private lateinit var recyclerViewResults: RecyclerView
    private lateinit var adapterResults: ResultsAdapter

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var job: Job? = null

    data class ScanResult(
        val host: String,
        val port: Int,
        val state: String,
        val service: String?,
        val version: String?,
        val product: String?,
        val banner: String?
    )

    data class ResultsAdapterItem(val result: ScanResult) {
        override fun toString() = "${result.host}:${result.port}/${result.state}"
    }

    inner class ResultsAdapter(
        private val items: MutableList<ScanResult>,
        private val onPortClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

        inner class ViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                background = ContextCompat.getDrawable(parent.context, android.R.drawable.btn_default)
                setPadding(16, 16, 16, 16)
            }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tv.text = "${item.host}:${item.port} - ${item.state} (${item.service ?: "unknown"})"
            if (item.state == "open") {
                holder.tv.setOnClickListener { onPortClick(item.port) }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private val SERVICE_PORTS = mapOf(
            22 to listOf("ssh"),
            80 to listOf("http"),
            443 to listOf("https"),
            21 to listOf("ftp"),
            23 to listOf("telnet"),
            25 to listOf("smtp"),
            53 to listOf("dns"),
            8080 to listOf("http-proxy"),
            3306 to listOf("mysql"),
            5432 to listOf("postgresql"),
            6379 to listOf("redis"),
            27017 to listOf("mongodb")
        )

        private val SERVICE_PROGS = mapOf(
            "ssh" to listOf("OpenSSH", "libssh"),
            "http" to listOf("Apache", "nginx", "IIS", "Tomcat"),
            "https" to listOf("Apache", "nginx", "IIS")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        etTargetHost = EditText(this).apply {
            hint = "Target Host / IP"
            setText("127.0.0.1")
        }
        etTargetPort = EditText(this).apply {
            hint = "Ports (e.g. 80,443 or blank for 1-1024)"
        }
        btnScan = Button(this).apply {
            text = "Start Scan"
            setOnClickListener {
                job?.cancel()
                job = scope.launch { scanWithProgress() }
            }
        }
        tvResults = TextView(this).apply {
            text = "Ready to scan...\n"
            textSize = 14f
        }
        recyclerViewResults = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NetworkScannerActivity)
        }

        layout.addView(etTargetHost)
        layout.addView(etTargetPort)
        layout.addView(btnScan)
        layout.addView(tvResults)
        layout.addView(recyclerViewResults)

        setContentView(layout)
    }

    private suspend fun scanWithProgress() = withContext(Dispatchers.Main) {
        try {
            val host = etTargetHost.text.toString().trim()
            if (host.isBlank()) throw Exception("Podaj adres IP lub nazwę hosta")

            val targetPortStr = etTargetPort.text.toString().trim()
            val allPorts = if (targetPortStr.isBlank()) {
                (1..1024).toList()
            } else {
                targetPortStr.split(",").mapNotNull { it.toIntOrNull() }
            }

            val mode: ScannerMode = if (allPorts.isEmpty()) ScannerMode.PASSIVE else ScannerMode.AGGRESSIVE

            tvResults.text = "Inicjuję scan (${mode.name})...\n\n"

            val resultsList = mutableListOf<ScanResult>()
            adapterResults = ResultsAdapter(resultsList) { port ->
                Toast.makeText(this@NetworkScannerActivity, "Clicked port $port", Toast.LENGTH_SHORT).show()
            }
            recyclerViewResults.adapter = adapterResults

            withContext(Dispatchers.IO) {
                when (mode) {
                    ScannerMode.PASSIVE -> {
                        val hostInfo = try {
                            InetAddress.getAllByName(host).firstOrNull()?.hostAddress ?: host
                        } catch (e: Exception) { host }

                        withContext(Dispatchers.Main) {
                            tvResults.append("DNS resolution: $hostInfo\n\n")
                        }

                        for (port in allPorts) {
                            val service = SERVICE_PORTS[port]?.firstOrNull()
                            val banner = bannerGrabbing(host, port, service)
                            if (banner != null) {
                                val result = ScanResult(host, port, "open", service, null, null, banner)
                                resultsList.add(result)
                                withContext(Dispatchers.Main) {
                                    adapterResults.notifyItemInserted(resultsList.size - 1)
                                    tvResults.append("$host:$port [open] $service\n")
                                }
                            }
                        }
                    }
                    ScannerMode.AGGRESSIVE -> {
                        withContext(Dispatchers.Main) {
                            tvResults.append("Przeskanowuję porty...\n\n")
                        }

                        for (port in allPorts) {
                            val state = scanPort(host, port)
                            if (state == "open") {
                                val serviceInfo = detectService(host, port)
                                val banner = bannerGrabbing(host, port, SERVICE_PORTS[port]?.firstOrNull())
                                val result = ScanResult(
                                    host, port, "open",
                                    serviceInfo.service,
                                    serviceInfo.version,
                                    serviceInfo.product,
                                    banner
                                )
                                resultsList.add(result)
                                withContext(Dispatchers.Main) {
                                    adapterResults.notifyItemInserted(resultsList.size - 1)
                                    tvResults.append("$host:$port - open (${serviceInfo.service})\n")
                                }
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            tvResults.append("\nError: ${e.message ?: "Unknown error"}\n")
        }
    }

    private fun scanPort(host: String, port: Int): String {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 500)
            socket.close()
            "open"
        } catch (e: Exception) {
            "closed"
        }
    }

    private fun detectService(host: String, port: Int): ScanResult {
        val servicePort = SERVICE_PORTS[port]?.firstOrNull() ?: "unknown"
        return ScanResult(
            host = host,
            port = port,
            state = "open",
            service = servicePort,
            version = null,
            product = null,
            banner = null
        )
    }

    private fun bannerGrabbing(host: String, port: Int, expectedService: String?): String? {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 500)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val banner = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                banner.append(line).append("\n")
                if (banner.length > 500) break
            }
            socket.close()
            banner.toString().ifBlank { expectedService }
        } catch (e: Exception) {
            expectedService
        }
    }

    override fun onResume() {
        super.onResume()
    }
}

enum class ScannerMode {
    PASSIVE,
    AGGRESSIVE
}
