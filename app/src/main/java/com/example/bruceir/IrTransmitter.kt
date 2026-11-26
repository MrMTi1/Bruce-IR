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
        INTERNAL, USB, WIFI, BRUCE_BLE
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
        val safePattern = if (pattern.size > 600) pattern.take(600).toIntArray() else pattern
        when (currentMode) {
            Mode.INTERNAL -> {
                try {
                    irManager?.transmit(frequency, safePattern)
                } catch (e: Exception) {
                    Log.e("IrTransmitter", "Internal Transmit Error: ${e.message}")
                }
            }
            Mode.USB -> {
                transmitUsb(frequency, safePattern)
            }
            Mode.WIFI -> {
                transmitWifi(frequency, safePattern)
            }
            Mode.BRUCE_BLE -> {
                if (!BruceBleIrLink.sendRaw(context, frequency, safePattern)) {
                    Log.e("IrTransmitter", "IR command is invalid or too large for BLE.")
                }
            }
        }
    }

    private fun transmitWifi(frequency: Int, pattern: IntArray) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        val user = prefs.getString("bruce_user", "admin") ?: "admin"
        val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"
        
        var fUrl = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        if (!fUrl.endsWith("/")) fUrl += "/"

        Thread {
            try {
                val conn = java.net.URL("${fUrl}cm").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    val auth = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $auth")
                }
                conn.connectTimeout = 3000
                val samples = pattern.joinToString(" ")
                val command = "ir tx_raw $frequency $samples"
                val body = "cmnd=" + java.net.URLEncoder.encode(command, "UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Log.d("IrTransmitter", "WiFi IR transmit accepted by Bruce")
                } else {
                    Log.e("IrTransmitter", "WiFi IR transmit rejected: HTTP $code")
                }
            } catch (e: Exception) {
                Log.e("IrTransmitter", "WiFi Transmit Failed: ${e.message}")
            }
        }.start()
    }

    private fun transmitUsb(frequency: Int, pattern: IntArray) {
        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device) ?: return

        try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val samples = pattern.joinToString(" ")
            val cmd = "ir tx_raw $frequency $samples\n"
            port.write(cmd.toByteArray(), 1000)
            port.close()
        } catch (e: Exception) {
            Log.e("IrTransmitter", "USB Transmit Error: ${e.message}")
        }
    }
}
