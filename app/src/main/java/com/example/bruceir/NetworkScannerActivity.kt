package com.example.bruceir

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.InetAddress
import java.util.concurrent.Executors

class NetworkScannerActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: HostAdapter
    private val foundHosts = mutableListOf<String>()

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
            startScan()
        }
    }

    private fun startScan() {
        foundHosts.clear()
        adapter.notifyDataSetChanged()
        val pb = findViewById<ProgressBar>(R.id.pbScan)
        val tvStatus = findViewById<TextView>(R.id.tvScanStatus)
        pb.visibility = View.VISIBLE
        pb.progress = 0
        tvStatus.text = getString(R.string.net_scanner_scanning)

        val executor = Executors.newFixedThreadPool(20)
        val subnet = getSubnet()
        
        for (i in 1..254) {
            executor.execute {
                try {
                    val host = "$subnet.$i"
                    val address = InetAddress.getByName(host)
                    if (address.isReachable(800)) {
                        runOnUiThread {
                            foundHosts.add(host)
                            foundHosts.sort()
                            adapter.notifyDataSetChanged()
                        }
                    }
                } catch (e: Exception) {}
                runOnUiThread {
                    pb.progress += 1
                    if (pb.progress >= 254) {
                        pb.visibility = View.GONE
                        tvStatus.text = getString(R.string.net_scanner_found, foundHosts.size)
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
            if (ip.contains(".") && !ip.startsWith("127.")) {
                return ip.substringBeforeLast(".")
            }
        }
        return "192.168.1"
    }

    class HostAdapter(private val hosts: List<String>) : RecyclerView.Adapter<HostAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = hosts[position]
        }
        override fun getItemCount() = hosts.size
        class VH(v: View) : RecyclerView.ViewHolder(v) { val tv: TextView = v.findViewById(android.R.id.text1) }
    }
}
