package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

class BruceBleConsoleActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectButton: Button
    private lateinit var helpButton: Button
    private lateinit var captureButton: Button
    private lateinit var commandInput: EditText
    private lateinit var sendButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val responseBuffer = StringBuilder()
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var serialCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var isReady = false
    private var commandInFlight = false
    private var writeInFlight = false
    private var captureInProgress = false
    private var autoCaptureStarted = false
    private var maxWriteBytes = 20

    private val scanTimeout = Runnable {
        if (isScanning) {
            stopScan()
            setStatus("Bruce BLE not found. Enable Config > Toggle BLE API on Bruce.")
        }
    }

    private val captureTimeout = Runnable {
        if (captureInProgress) {
            captureInProgress = false
            commandInFlight = false
            responseBuffer.clear()
            setStatus("IR capture timed out. Try again.")
            updateControls()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (requiredPermissions().all { grants[it] == true || hasPermission(it) }) {
            startScan()
        } else {
            setStatus("Bluetooth permission is required to connect to Bruce.")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startScan()
        else setStatus("Bluetooth is disabled.")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                if (!isScanning) return@runOnUiThread
                stopScan()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                stopScan()
                setStatus("Bluetooth scan failed ($errorCode).")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    if (this@BruceBleConsoleActivity.gatt === gatt) {
                        setStatus("Bluetooth connection failed ($status).")
                        clearConnection(closeGatt = true)
                    }
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                    runOnUiThread { setStatus("Connected; discovering Bruce services...") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread {
                        if (this@BruceBleConsoleActivity.gatt === gatt) {
                            setStatus("Disconnected.")
                            clearConnection(closeGatt = true)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                maxWriteBytes = (mtu - 3).coerceAtLeast(20)
            }
            serialCharacteristic?.let { enableNotifications(gatt, it) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { setStatus("Could not discover Bruce BLE services.") }
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                runOnUiThread { setStatus("Bruce BLE serial characteristic not found.") }
                return
            }

            serialCharacteristic = characteristic
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                runOnUiThread { setStatus("Could not enable Bruce BLE notifications.") }
                return
            }
            if (gatt.requestMtu(DESIRED_BLE_MTU)) return
            enableNotifications(gatt, characteristic)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                runOnUiThread { setStatus("Bruce BLE notification descriptor not found.") }
                return
            }

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (gatt.writeDescriptor(descriptor)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
            }
            if (result != BluetoothStatusCodes.SUCCESS) {
                runOnUiThread { setStatus("Could not subscribe to Bruce BLE output.") }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isReady = true
                    if (intent.getBooleanExtra(EXTRA_START_IR_CAPTURE, false) && !autoCaptureStarted) {
                        autoCaptureStarted = true
                        setStatus("Connected. Starting live IR capture…")
                        handler.post { sendCommand("ir rx -raw", isIrCapture = true) }
                    } else {
                        setStatus("Connected to Bruce. Use HELP to list commands.")
                    }
                } else {
                    setStatus("Could not subscribe to Bruce BLE output ($status).")
                }
                updateControls()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            runOnUiThread {
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pendingWrites.clear()
                    commandInFlight = false
                    captureInProgress = false
                    setStatus("Bluetooth command write failed ($status).")
                    updateControls()
                } else {
                    writeNextChunk()
                }
            }
        }

        @Deprecated("Used by Android versions before API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            receiveOutput(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            receiveOutput(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        setContentView(R.layout.activity_bruce_ble_console)

        statusView = findViewById(R.id.tvBleStatus)
        logView = findViewById(R.id.tvBleLog)
        logScroll = findViewById(R.id.svBleLog)
        connectButton = findViewById(R.id.btnBleConnect)
        helpButton = findViewById(R.id.btnBleHelp)
        captureButton = findViewById(R.id.btnBleCapture)
        commandInput = findViewById(R.id.etBleCommand)
        sendButton = findViewById(R.id.btnBleSend)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        findViewById<ImageButton>(R.id.btnBleBack).setOnClickListener { finish() }
        connectButton.setOnClickListener {
            when {
                isScanning -> stopScan()
                gatt != null -> disconnect()
                else -> startScan()
            }
        }
        helpButton.setOnClickListener { sendCommand("help") }
        captureButton.setOnClickListener { sendCommand("ir rx -raw", isIrCapture = true) }
        sendButton.setOnClickListener { submitCommand() }
        commandInput.setOnEditorActionListener { _, _, _ ->
            submitCommand()
            true
        }
        updateControls()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        if (requiredPermissions().any { !hasPermission(it) }) {
            permissionLauncher.launch(requiredPermissions())
            return
        }

        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            setStatus("This phone does not support Bluetooth.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            setStatus("Bluetooth LE scanning is unavailable.")
            return
        }

        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            setStatus("Looking for Bruce BLE...")
            updateControls()
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission was denied.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (isScanning) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                setStatus("Bluetooth permission was denied.")
            }
        }
        isScanning = false
        updateControls()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (requiredPermissions().any { !hasPermission(it) }) {
            setStatus("Bluetooth permission was denied.")
            return
        }
        setStatus("Connecting to Bruce…")
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            updateControls()
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission was denied.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        isReady = false
        commandInFlight = false
        captureInProgress = false
        pendingWrites.clear()
        handler.removeCallbacks(captureTimeout)
        val currentGatt = gatt
        gatt = null
        serialCharacteristic = null
        currentGatt?.disconnect()
        currentGatt?.close()
        setStatus("Disconnected.")
        updateControls()
    }

    private fun clearConnection(closeGatt: Boolean) {
        val currentGatt = gatt
        gatt = null
        serialCharacteristic = null
        isReady = false
        commandInFlight = false
        writeInFlight = false
        captureInProgress = false
        pendingWrites.clear()
        responseBuffer.clear()
        handler.removeCallbacks(captureTimeout)
        if (closeGatt) currentGatt?.close()
        updateControls()
    }

    private fun submitCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isNotEmpty() && sendCommand(command)) commandInput.text.clear()
    }

    private fun sendCommand(command: String, isIrCapture: Boolean = false): Boolean {
        if (!isReady || commandInFlight) {
            Toast.makeText(this, "Connect to Bruce and wait for the previous command.", Toast.LENGTH_SHORT).show()
            return false
        }

        val bytes = (command.trimEnd('\r', '\n') + "\n").toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_COMMAND_BYTES) {
            setStatus("Command is too long (maximum $MAX_COMMAND_BYTES bytes).")
            return false
        }

        responseBuffer.clear()
        captureInProgress = isIrCapture
        commandInFlight = true
        if (isIrCapture) handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
        appendLog("\n> $command\n")

        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + maxWriteBytes).coerceAtMost(bytes.size)
            pendingWrites.addLast(bytes.copyOfRange(offset, end))
            offset = end
        }
        updateControls()
        writeNextChunk()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun writeNextChunk() {
        if (writeInFlight || !isReady) return
        val chunk = pendingWrites.pollFirst() ?: return
        val currentGatt = gatt ?: return
        val characteristic = serialCharacteristic ?: return

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }

        if (started) {
            writeInFlight = true
        } else {
            pendingWrites.clear()
            commandInFlight = false
            captureInProgress = false
            setStatus("Could not send Bluetooth command.")
            updateControls()
        }
    }

    private fun receiveOutput(bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        runOnUiThread {
            appendLog(text)
            if (!commandInFlight) return@runOnUiThread

            responseBuffer.append(text)
            if (responseBuffer.endsWith("# ")) {
                commandInFlight = false
                handler.removeCallbacks(captureTimeout)
                if (captureInProgress) finishIrCapture()
                else updateControls()
            }
        }
    }

    private fun finishIrCapture() {
        captureInProgress = false
        val response = responseBuffer.toString()
        val start = response.indexOf(IR_FILE_HEADER)
        val prompt = response.lastIndexOf("# ")
        if (start < 0 || prompt <= start) {
            setStatus("No IR signal was captured.")
            updateControls()
            return
        }

        val capture = response.substring(start, prompt).trim()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_IR_CAPTURE, capture))
        finish()
    }

    private fun appendLog(text: String) {
        logView.append(text)
        if (logView.length() > MAX_LOG_CHARS) {
            logView.text = logView.text.subSequence(logView.length() - MAX_LOG_CHARS, logView.length())
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(message: String) {
        statusView.text = message
        updateControls()
    }

    private fun updateControls() {
        if (!::connectButton.isInitialized) return
        connectButton.text = when {
            isScanning -> "STOP SCAN"
            gatt != null -> "DISCONNECT"
            else -> "CONNECT"
        }
        val canSend = isReady && !commandInFlight
        helpButton.isEnabled = canSend
        captureButton.isEnabled = canSend
        sendButton.isEnabled = canSend
        commandInput.isEnabled = canSend
    }

    override fun onDestroy() {
        stopScan()
        handler.removeCallbacks(captureTimeout)
        gatt?.close()
        gatt = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IR_CAPTURE = "com.example.bruceir.EXTRA_IR_CAPTURE"
        const val EXTRA_START_IR_CAPTURE = "com.example.bruceir.EXTRA_START_IR_CAPTURE"

        private val SERVICE_UUID = UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91")
        private val CHARACTERISTIC_UUID = UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val DESIRED_BLE_MTU = 247
        private const val CAPTURE_TIMEOUT_MS = 18_000L
        private const val MAX_COMMAND_BYTES = 512
        private const val MAX_LOG_CHARS = 24_000
        private const val IR_FILE_HEADER = "Filetype: IR signals file"
    }
}
