package com.example.bruceir

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class NetworkScannerActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: HostAdapter
    private val foundHosts = mutableListOf<DeviceInfo>()

    data class DeviceInfo(
        val ip: String,
        val hostname: String,
        val ports: String,
        val deviceType: String,
        val manufacturer: String = "Unknown"
    )

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_scanner)

        rv = findViewById(R.id.rvHosts)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = HostAdapter(foundHosts)
        rv.adapter = adapter

        findViewById<Button>(R.id.btnStartScan).setOnClickListener {
            startDeepScan()
        }
    }

    private fun startDeepScan() {
        foundHosts.clear()
        adapter.notifyDataSetChanged()
        val pb = findViewById<ProgressBar>(R.id.pbScan)
        val tvStatus = findViewById<TextView>(R.id.tvScanStatus)
        pb.visibility = View.VISIBLE
        pb.progress = 0
        tvStatus.text = getString(R.string.net_scanner_scanning)

        val executor = Executors.newFixedThreadPool(40) // Więcej wątków dla szybkości
        val subnet = getSubnet()
        
        // Mapa portów do nazw usług dla łatwiejszej identyfikacji
        val portMap = mapOf(
            21 to "FTP",
            22 to "SSH",
            23 to "Telnet",
            25 to "SMTP",
            80 to "HTTP",
            139 to "NetBIOS",
            443 to "HTTPS",
            445 to "SMB",
            1433 to "MSSQL",
            3306 to "MySQL",
            3389 to "RDP",
            5432 to "Postgres",
            5900 to "VNC",
            8080 to "HTTP-Proxy"
        )
        
        for (i in 1..254) {
            executor.execute {
                val host = "$subnet.$i"
                try {
                    val address = InetAddress.getByName(host)
                    if (address.isReachable(400)) {
                        val hostname = address.canonicalHostName
                        val detectedServices = mutableListOf<String>()
                        
                        // Głęboki skan portów z mapy
                        for ((port, service) in portMap) {
                            try {
                                val s = Socket()
                                s.connect(InetSocketAddress(host, port), 80) // Bardzo krótki timeout
                                s.close()
                                detectedServices.add("$port ($service)")
                            } catch (e: Exception) {}
                        }
                        
                        runOnUiThread {
                            val info = if (detectedServices.isEmpty()) "No open ports" else detectedServices.joinToString(", ")
                            foundHosts.add(DeviceInfo(host, hostname, info, "General Device"))
                            foundHosts.sortBy { it.ip }
                            adapter.notifyDataSetChanged()
                        }
                    }
                } catch (e: Exception) {}
                runOnUiThread {
                    pb.progress += 1
                    if (pb.progress >= 254) {
                        pb.visibility = View.GONE
                        tvStatus.text = "Deep Scan Complete"
                    }
                }
            }
        }
    }

    private fun getSubnet(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val linkProps = cm.getLinkProperties(cm.activeNetwork)
        for (addr in linkProps?.linkAddresses ?: emptyList()) {
            val ip = addr.address.hostAddress ?: ""
            if (ip.contains(".") && !ip.startsWith("127.")) return ip.substringBeforeLast(".")
        }
        return "192.168.1"
    }

    class HostAdapter(private val hosts: List<DeviceInfo>) : RecyclerView.Adapter<HostAdapter.VH>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = hosts[position]
            holder.t1.text = "${item.ip} (${item.hostname})"
            holder.t2.text = item.ports
        }
        override fun getItemCount() = hosts.size
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val t1: TextView = v.findViewById(android.R.id.text1)
            val t2: TextView = v.findViewById(android.R.id.text2)
        }
    }
}
