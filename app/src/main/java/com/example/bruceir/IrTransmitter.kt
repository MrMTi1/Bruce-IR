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
        INTERNAL, USB
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
        if (currentMode == Mode.INTERNAL) {
            try {
                irManager?.transmit(frequency, pattern)
            } catch (e: Exception) {
                Log.e("IrTransmitter", "Internal Transmit Error: ${e.message}")
            }
        } else {
            transmitUsb(frequency, pattern)
        }
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
