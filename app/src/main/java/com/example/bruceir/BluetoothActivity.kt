package com.example.bruceir

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class BluetoothActivity : AppCompatActivity() {

    private var hidDevice: BluetoothHidDevice? = null
    private var targetDevice: BluetoothDevice? = null
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

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
        setContentView(R.layout.activity_bluetooth)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "HID Device requires Android 9.0+", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupHid()

        findViewById<Button>(R.id.btnBtPlay).setOnClickListener { sendKey(0xCD.toByte()) }
        findViewById<Button>(R.id.btnBtNext).setOnClickListener { sendKey(0xB5.toByte()) }
        findViewById<Button>(R.id.btnBtPrev).setOnClickListener { sendKey(0xB6.toByte()) }
        findViewById<Button>(R.id.btnBtVolUp).setOnClickListener { sendKey(0xE9.toByte()) }
        findViewById<Button>(R.id.btnBtVolDown).setOnClickListener { sendKey(0xEA.toByte()) }
        findViewById<Button>(R.id.btnBtSelfie).setOnClickListener { sendKey(0x1E.toByte()) }
    }

    private fun setupHid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        registerApp()
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    hidDevice = null
                }
            }, BluetoothProfile.HID_DEVICE)
        }
    }

    private fun registerApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Bruce Remote", "Android HID", "Google", 0xC0.toByte(),
                byteArrayOf(
                    0x05, 0x01, 0x09, 0x06, 0xa1.toByte(), 0x01, 0x85.toByte(), 0x01, 0x05, 0x07, 0x19, 0xe0.toByte(), 0x29, 0xe7.toByte(), 0x15, 0x00,
                    0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01, 0x95.toByte(), 0x05,
                    0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01,
                    0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
                    0xc0.toByte()
                )
            )
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 31) {
                return
            }

            hidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), object : BluetoothHidDevice.Callback() {
                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    targetDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                    runOnUiThread {
                        val name = try { device?.name ?: "Unknown" } catch(e: SecurityException) { "Device" }
                        findViewById<TextView>(R.id.tvBtStatus).text = "Status: ${if (state == BluetoothProfile.STATE_CONNECTED) "Connected to $name" else "Disconnected"}"
                    }
                }
            })
        }
    }

    private fun sendKey(keyCode: Byte) {
        val device = targetDevice ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val report = byteArrayOf(0, 0, keyCode, 0, 0, 0, 0, 0)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 31) {
                hidDevice?.sendReport(device, 1, report)
                Executors.newSingleThreadExecutor().execute {
                    Thread.sleep(50)
                    hidDevice?.sendReport(device, 1, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 31) {
                    hidDevice?.unregisterApp()
                }
            } catch (e: SecurityException) {}
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        }
    }
}
