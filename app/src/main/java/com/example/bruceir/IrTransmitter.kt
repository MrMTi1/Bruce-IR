package com.example.bruceir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class IrTransmitter(private val context: Context) {

    private val irManager: ConsumerIrManager? = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    enum class Mode {
        INTERNAL, USB, WIFI
    }

    var currentMode: Mode
        get() {
            val prefs = context.getSharedPreferences("transmitter_prefs", Context.MODE_PRIVATE)
            return try {
                Mode.valueOf(prefs.getString("mode", Mode.INTERNAL.name) ?: Mode.INTERNAL.name)
            } catch (e: Exception) {
                Mode.INTERNAL
            }
        }
        set(value) {
            val prefs = context.getSharedPreferences("transmitter_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("mode", value.name).apply()
        }

    fun hasInternalIr(): Boolean = irManager?.hasIrEmitter() == true

    fun isUsbDeviceConnected(): Boolean {
        return try {
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            availableDrivers.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun transmit(frequency: Int, pattern: IntArray) {
        when (currentMode) {
            Mode.INTERNAL -> {
                try {
                    irManager?.transmit(frequency, pattern)
                } catch (e: Exception) {
                    Log.e("IrTransmitter", "Internal Transmit Error: ${e.message}")
                }
            }
            Mode.USB -> transmitUsb(frequency, pattern)
            Mode.WIFI -> transmitWifi(frequency, pattern)
        }
    }

    private fun transmitWifi(frequency: Int, pattern: IntArray) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        val user = prefs.getString("bruce_user", "admin") ?: "admin"
        val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        Thread {
            try {
                // Przykładowy endpoint dla Bruce Firmware do wysyłania IR
                // Format: /ir?freq=38000&data=100 200 300...
                val dataStr = pattern.joinToString(" ")
                val url = "$baseUrl/ir?send=true&freq=$frequency&data=$dataStr"
                BruceUtils.downloadFileContent(url, user, pass)
            } catch (e: Exception) {
                Log.e("IrTransmitter", "WiFi Transmit Error: ${e.message}")
            }
        }.start()
    }

    private fun transmitUsb(frequency: Int, pattern: IntArray) {
        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device) ?: return
        val port = driver.ports[0]

        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            // Format: "S freq count p1 p2 ..."
            val cmd = "S $frequency ${pattern.size} ${pattern.joinToString(" ")}\n"
            port.write(cmd.toByteArray(), 1000)
            
        } catch (e: Exception) {
            Log.e("IrTransmitter", "USB Transmit Error: ${e.message}")
        } finally {
            try {
                port.close()
            } catch (e: Exception) {}
        }
    }
}
