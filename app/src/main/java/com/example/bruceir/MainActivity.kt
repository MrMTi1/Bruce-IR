package com.example.bruceir

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bruceir.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var transmitter: IrTransmitter
    private lateinit var adapter: CommandAdapter
    private var allData = IrFolder("ROOT")
    private var currentFolder = allData
    private var isBruceOnline = false
    private var isListView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transmitter = IrTransmitter(this)
        setupNavigation()
        setupRemotes()
        setupCyberTools()
        setupSystemTools()
        setupHeaderActions()
        
        load()
        startHeartbeat()
    }

    private fun startHeartbeat() {
        val handler = Handler(Looper.getMainLooper())
        val checkTask = object : Runnable {
            override fun run() {
                Thread {
                    val online = try {
                        val conn = URL("http://bruce.local/ping").openConnection()
                        conn.connectTimeout = 800
                        conn.getInputStream().close()
                        true
                    } catch (e: Exception) { false }
                    
                    runOnUiThread {
                        isBruceOnline = online
                        findViewById<View>(R.id.vStatusDot).setBackgroundColor(if (online) Color.GREEN else Color.RED)
                        findViewById<TextView>(R.id.tvHeaderTitle).text = if (online) "BRUCE ONLINE" else "BRUCE OFFLINE"
                        findViewById<TextView>(R.id.tvHeaderTitle).setTextColor(if (online) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
                    }
                }.start()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(checkTask)
    }

    private fun setupNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val sRemotes = findViewById<View>(R.id.sectionRemotes)
        val sCyber = findViewById<View>(R.id.sectionCyber)
        val sSystem = findViewById<View>(R.id.sectionSystem)

        nav.setOnItemSelectedListener { item ->
            sRemotes.visibility = View.GONE
            sCyber.visibility = View.GONE
            sSystem.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_remotes -> sRemotes.visibility = View.VISIBLE
                R.id.nav_cyber -> sCyber.visibility = View.VISIBLE
                R.id.nav_system -> sSystem.visibility = View.VISIBLE
            }
            true
        }
    }

    private fun setupCyberTools() {
        findViewById<Button>(R.id.btnCyberTpms).setOnClickListener { 
            if (isBruceOnline) showTpmsDialog() else Toast.makeText(this, "Connect to Bruce first", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnCyberSubGhz).setOnClickListener { 
            startActivity(Intent(this, SubGhzActivity::class.java)) 
        }
        findViewById<Button>(R.id.btnCyberImmo).setOnClickListener { 
            showRfidDialog()
        }
        findViewById<Button>(R.id.btnCyberBle).setOnClickListener {
            startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "ble") })
        }
        findViewById<Button>(R.id.btnCyberC2).setOnClickListener {
            startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "bridge") })
        }
        findViewById<Button>(R.id.btnCyberWps).setOnClickListener {
            startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "wps") })
        }
    }

    private fun setupSystemTools() {
        findViewById<Button>(R.id.btnSysNet).setOnClickListener { startActivity(Intent(this, NetworkScannerActivity::class.java)) }
        findViewById<Button>(R.id.btnSysConsole).setOnClickListener { startActivity(Intent(this, SerialConsoleActivity::class.java)) }
        findViewById<Button>(R.id.btnSysNav).setOnClickListener { if (isBruceOnline) startActivity(Intent(this, NavigatorActivity::class.java)) }
    }

    private fun setupHeaderActions() {
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            val et = findViewById<EditText>(R.id.etSearch)
            et.visibility = if (et.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<ImageButton>(R.id.btnViewMode).setOnClickListener {
            isListView = !isListView
            val lm = findViewById<RecyclerView>(R.id.recyclerView).layoutManager as GridLayoutManager
            lm.spanCount = if (isListView) 1 else 3
            adapter.notifyDataSetChanged()
        }
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener {
            Toast.makeText(this, "Lock Toggled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTpmsDialog() {
        Toast.makeText(this, "Opening TPMS Analysis...", Toast.LENGTH_SHORT).show()
    }

    private fun showRfidDialog() {
        Toast.makeText(this, "RFID Sniffer Ready", Toast.LENGTH_SHORT).show()
    }

    private fun setupRemotes() {
        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = CommandAdapter(currentFolder.items, null, { action, item, pos ->
            if (action == CommandAdapter.ActionType.OPEN && item is IrFolder) {
                currentFolder = item
                adapter.updateList(currentFolder.items)
            }
        }, { cmd ->
            transmitter.transmit(cmd.frequency, cmd.pattern)
        })
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter
    }

    private fun load() {
        allData = BruceUtils.loadAllData(this)
        currentFolder = allData
        adapter.updateList(currentFolder.items)
    }
}
