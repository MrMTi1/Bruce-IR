package com.example.bruceir

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

class SerialConsoleActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serial_console)

        tvLog = findViewById(R.id.tvSerialLog)
        svLog = findViewById(R.id.svLog)

        findViewById<Button>(R.id.btnSerialSend).setOnClickListener {
            val et = findViewById<EditText>(R.id.etSerialCmd)
            sendData(et.text.toString() + "\n")
            et.setText("")
        }

        connect()
    }

    private fun connect() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            tvLog.append("No USB devices found.\n")
            return
        }

        val driver = drivers[0]
        val connection = usbManager.openDevice(driver.device) ?: return
        
        port = driver.ports[0]
        try {
            port?.open(connection)
            port?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            ioManager = SerialInputOutputManager(port, this)
            ioManager?.start()
            tvLog.append("Connected to ${driver.device.deviceName}\n")
        } catch (e: Exception) {
            tvLog.append("Connect Error: ${e.message}\n")
        }
    }

    private fun sendData(data: String) {
        try {
            port?.write(data.toByteArray(), 1000)
        } catch (e: Exception) {
            tvLog.append("Write Error: ${e.message}\n")
        }
    }

    override fun onNewData(data: ByteArray?) {
        mainHandler.post {
            tvLog.append(String(data ?: byteArrayOf()))
            svLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onRunError(e: java.lang.Exception?) {
        mainHandler.post { tvLog.append("Error: ${e?.message}\n") }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioManager?.stop()
        port?.close()
    }
}
