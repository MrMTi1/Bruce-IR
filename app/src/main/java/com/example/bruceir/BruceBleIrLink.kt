package com.example.bruceir

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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.widget.Toast
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

object BruceBleIrLink {
    private val handler = Handler(Looper.getMainLooper())
    private val commands = ArrayDeque<String>()
    private val writes = ArrayDeque<ByteArray>()

    private var context: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var serialCharacteristic: BluetoothGattCharacteristic? = null
    private var listener: ((String) -> Unit)? = null
    private var isScanning = false
    private var isConnecting = false
    private var isReady = false
    private var writeInFlight = false
    private var waitingForPrompt = false
    private var maxWriteBytes = 20
    private var receiveBuffer = StringBuilder()
    @Volatile private var currentStatus = "Not connected"

    private val scanTimeout = Runnable {
        if (isScanning) {
            stopScan()
            setStatus("LilyGo not found. Make sure BLE API is enabled.")
        }
    }

    private val commandTimeout = Runnable {
        if (waitingForPrompt) {
            waitingForPrompt = false
            receiveBuffer.clear()
            setStatus("LilyGo did not respond to the IR command.")
            runNextCommand()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isScanning) return
            val device = result.device
            val name = device.name.orEmpty()
            val uuids = result.scanRecord?.serviceUuids

            val isMatch = name.contains("LilyGo", ignoreCase = true) ||
                    name.contains("Bruce", ignoreCase = true) ||
                    name.contains("Smart TV", ignoreCase = true) ||
                    name.contains("Remote", ignoreCase = true) ||
                    uuids?.any { it.uuid == SERVICE_UUID } == true

            if (isMatch) {
                stopScan()
                isConnecting = true
                setStatus("Found LilyGo ($name). Connecting…")
                try {
                    val connectedGatt = device.connectGatt(
                        context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                    )
                    if (connectedGatt == null) {
                        isConnecting = false
                        setStatus("Could not connect to LilyGo.")
                    } else {
                        gatt = connectedGatt
                    }
                } catch (exception: SecurityException) {
                    isConnecting = false
                    setStatus("Bluetooth permission is required.")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            setStatus("Bluetooth scan failed ($errorCode).")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    if (this@BruceBleIrLink.gatt === gatt) {
                        clearConnection(gatt)
                        setStatus("LilyGo rozłączone. Automatyczne ponowne łączenie...")
                        handler.postDelayed({
                            if (!isReady && !isScanning && !isConnecting) {
                                context?.let { connect(it) }
                            }
                        }, 2000L)
                    }
                }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.post {
                    isReady = true
                    isConnecting = false
                    setStatus("Połączono z LilyGO")
                    runNextCommand()
                }
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxWriteBytes = (mtu - 3).coerceAtLeast(20)
            serialCharacteristic?.let { enableNotifications(gatt, it) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post {
                    clearConnection(gatt)
                    setStatus("Could not discover LilyGo BLE services.")
                }
                return
            }
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                handler.post {
                    clearConnection(gatt)
                    setStatus("LilyGo BLE serial service was not found.")
                }
                return
            }
            serialCharacteristic = characteristic
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                handler.post {
                    clearConnection(gatt)
                    setStatus("Could not enable LilyGo BLE notifications.")
                }
                return
            }
            if (!gatt.requestMtu(DESIRED_MTU)) enableNotifications(gatt, characteristic)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (isReady) return
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                handler.post {
                    clearConnection(gatt)
                    setStatus("LilyGo BLE notification descriptor is missing.")
                }
                return
            }
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (gatt.writeDescriptor(descriptor)) BluetoothStatusCodes.SUCCESS
                else BluetoothStatusCodes.ERROR_UNKNOWN
            }
            if (result != BluetoothStatusCodes.SUCCESS) {
                handler.post {
                    clearConnection(gatt)
                    setStatus("Could not subscribe to LilyGo BLE output.")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isConnecting = false
                    isReady = true
                    setStatus("Connected to LilyGo")
                    runNextCommand()
                } else {
                    clearConnection(gatt)
                    setStatus("Could not subscribe to LilyGo BLE output ($status).")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writes.clear()
                    waitingForPrompt = false
                    handler.removeCallbacks(commandTimeout)
                    setStatus("LilyGo BLE command write failed ($status).")
                    runNextCommand()
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
        ) = receiveOutput(value)
    }

    fun setListener(callback: ((String) -> Unit)?) {
        listener = callback
        callback?.invoke(currentStatus)
    }

    fun status(): String = currentStatus
    val isConnected: Boolean get() = isReady

    fun connect(context: Context) {
        val appContext = context.applicationContext
        handler.post {
            this.context = appContext
            if (isReady) {
                setStatus("Connected to LilyGo")
                return@post
            }
            startScan()
        }
    }

    fun sendRaw(context: Context, frequency: Int, pattern: IntArray): Boolean {
        if (frequency <= 0 || pattern.isEmpty() || pattern.any { it <= 0 }) return false
        val command = "ir tx_raw $frequency ${pattern.joinToString(" ")}"
        if (command.toByteArray(StandardCharsets.UTF_8).size >= MAX_COMMAND_BYTES) return false

        if (!isReady) {
            handler.post {
                Toast.makeText(context.applicationContext, "⚠️ BŁĄD: LilyGO nie jest połączone! Włącz urządzenie.", Toast.LENGTH_LONG).show()
            }
            setStatus("Błąd: LilyGO rozłączone.")
            return false
        }

        handler.post {
            if (commands.size >= MAX_QUEUED_COMMANDS) {
                setStatus("LilyGo command queue is full; try again shortly.")
                return@post
            }
            commands.addLast(command)
            runNextCommand()
        }
        return true
    }

    fun sendCustomCommand(context: Context, command: String): Boolean {
        if (command.isBlank()) return false
        val cmd = command.trim()
        if (cmd.toByteArray(StandardCharsets.UTF_8).size >= MAX_COMMAND_BYTES) return false

        if (!isReady) {
            handler.post {
                Toast.makeText(context.applicationContext, "⚠️ BŁĄD: LilyGO nie jest połączone! Włącz urządzenie.", Toast.LENGTH_LONG).show()
            }
            setStatus("Błąd: LilyGO rozłączone.")
            return false
        }

        handler.post {
            if (commands.size >= MAX_QUEUED_COMMANDS) {
                setStatus("LilyGo command queue is full.")
                return@post
            }
            commands.addLast(cmd)
            runNextCommand()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isReady || isScanning || isConnecting) return
        val appContext = context ?: return
        try {
            val bluetoothAdapter = adapter
                ?: appContext.getSystemService(BluetoothManager::class.java)?.adapter
            adapter = bluetoothAdapter
            if (bluetoothAdapter == null) {
                setStatus("This phone does not support Bluetooth.")
                return
            }
            if (!bluetoothAdapter.isEnabled) {
                setStatus("Turn on Bluetooth to connect to LilyGo.")
                return
            }
            val scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                setStatus("Bluetooth LE scanning is unavailable.")
                return
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            setStatus("Searching for LilyGo…")
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (exception: SecurityException) {
            setStatus("Bluetooth permission is required to connect to LilyGo.")
        } catch (exception: IllegalStateException) {
            setStatus("Could not start Bluetooth scan.")
            Log.e(TAG, "Could not start BLE scan", exception)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (isScanning) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (exception: SecurityException) {
                setStatus("Bluetooth permission was denied.")
            }
        }
        isScanning = false
    }

    private fun receiveOutput(bytes: ByteArray) {
        val text = String(bytes, StandardCharsets.UTF_8)
        handler.post {
            if (!waitingForPrompt) return@post
            receiveBuffer.append(text)
            if (receiveBuffer.length > MAX_RESPONSE_LENGTH) {
                receiveBuffer.delete(0, receiveBuffer.length - MAX_RESPONSE_LENGTH)
            }
            if (receiveBuffer.endsWith("# ")) {
                val failed = receiveBuffer.contains("ERROR:", ignoreCase = true)
                receiveBuffer.clear()
                waitingForPrompt = false
                handler.removeCallbacks(commandTimeout)
                setStatus(if (failed) "LilyGo rejected the IR command." else "IR command sent by LilyGo")
                runNextCommand()
            }
        }
    }

    private fun runNextCommand() {
        if (!isReady || waitingForPrompt || writeInFlight || writes.isNotEmpty()) return
        val command = commands.pollFirst() ?: return
        val bytes = (command + "\n").toByteArray(StandardCharsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + maxWriteBytes).coerceAtMost(bytes.size)
            writes.addLast(bytes.copyOfRange(offset, end))
            offset = end
        }
        receiveBuffer.clear()
        waitingForPrompt = true
        handler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS)
        writeNextChunk()
    }

    @SuppressLint("MissingPermission")
    private fun writeNextChunk() {
        if (writeInFlight || !isReady) return
        val chunk = writes.pollFirst()
        if (chunk == null) return
        val currentGatt = gatt
        val characteristic = serialCharacteristic
        if (currentGatt == null || characteristic == null) {
            clearConnection()
            setStatus("LilyGo disconnected before sending.")
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
            writes.clear()
            waitingForPrompt = false
            handler.removeCallbacks(commandTimeout)
            setStatus("Could not send IR command to LilyGo.")
            runNextCommand()
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearConnection(expectedGatt: BluetoothGatt? = null) {
        if (expectedGatt != null && gatt !== expectedGatt) return
        val currentGatt = gatt
        gatt = null
        serialCharacteristic = null
        isReady = false
        isConnecting = false
        writeInFlight = false
        writes.clear()
        waitingForPrompt = false
        handler.removeCallbacks(commandTimeout)
        if (currentGatt != null) {
            try {
                currentGatt.close()
            } catch (_: SecurityException) {
            }
        }
    }

    private fun setStatus(status: String) {
        currentStatus = status
        listener?.invoke(status)
    }

    private fun hasBluetoothPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private const val TAG = "BruceBleIrLink"
    private const val MAX_COMMAND_BYTES = 512
    private const val MAX_RESPONSE_LENGTH = 2048
    private const val MAX_QUEUED_COMMANDS = 20
    private const val SCAN_TIMEOUT_MS = 12_000L
    private const val COMMAND_TIMEOUT_MS = 12_000L
    private const val DESIRED_MTU = 247
    private val SERVICE_UUID = UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91")
    private val CHARACTERISTIC_UUID = UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
