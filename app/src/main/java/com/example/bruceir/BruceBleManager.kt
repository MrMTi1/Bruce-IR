package com.example.bruceir

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*

object BruceBleManager {

    private const val TAG = "BruceBleManager"

    val GOOGLE_TV_SERVICE_UUID = UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb")
    val HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

    val BRUCE_SERVICE_UUID = UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91")
    val BRUCE_CHAR_UUID = UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")

    val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var activeGatt: BluetoothGatt? = null
        private set

    var connectedDevice: BluetoothDevice? = null
        private set

    var isConnected = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<BleListener>()

    interface BleListener {
        fun onConnectionStateChanged(connected: Boolean, deviceName: String?)
        fun onServicesDiscovered(services: List<BluetoothGattService>)
        fun onDataReceived(characteristicUuid: UUID, text: String, hex: String, bytes: ByteArray)
        fun onError(message: String)
    }

    fun registerListener(listener: BleListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregisterListener(listener: BleListener) {
        listeners.remove(listener)
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, address: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bluetoothManager.adapter ?: return

        try {
            val device = adapter.getRemoteDevice(address)
            connectedDevice = device

            activeGatt?.close()
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context.applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context.applicationContext, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}")
            notifyError("Błąd łączenia BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerBonding(context: Context): Boolean {
        val device = connectedDevice ?: return false
        return try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                val success = device.createBond()
                Log.d(TAG, "createBond triggered: $success")
                success
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bonding error: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(cmd: String): Boolean {
        val gatt = activeGatt ?: return false
        val char = gatt.services.flatMap { it.characteristics }.firstOrNull {
            it.uuid == BRUCE_CHAR_UUID || (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
        } ?: return false

        val payload = (cmd.trim() + "\n").toByteArray(Charsets.UTF_8)
        return writeCharacteristicPayload(gatt, char, payload)
    }

    @SuppressLint("MissingPermission")
    fun sendTvKeycode(hidCode: Int): Boolean {
        val gatt = activeGatt ?: return false
        val char = gatt.services.flatMap { it.characteristics }.firstOrNull {
            it.uuid.toString().contains("fe36", true) ||
            it.uuid.toString().contains("1812", true) ||
            (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
        } ?: return false

        val keyDown = byteArrayOf(0x00, hidCode.toByte())
        val success = writeCharacteristicPayload(gatt, char, keyDown)

        handler.postDelayed({
            val keyUp = byteArrayOf(0x00, 0x00)
            writeCharacteristicPayload(gatt, char, keyUp)
        }, 50)

        return success
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicPayload(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write payload error: ${e.message}")
            false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                Log.d(TAG, "GATT Connected. Requesting MTU 512...")
                try { gatt.requestMtu(512) } catch (_: Exception) {}
                gatt.discoverServices()
                notifyConnectionState(true, gatt.device.name)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                notifyConnectionState(false, gatt.device.name)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Discovered ${gatt.services.size} GATT Services.")

                // Automatycznie włącz subskrypcję powiadomień dla Bruce CHAR
                val bruceChar = gatt.services.flatMap { it.characteristics }.firstOrNull { it.uuid == BRUCE_CHAR_UUID }
                if (bruceChar != null) {
                    try {
                        gatt.setCharacteristicNotification(bruceChar, true)
                        val descriptor = bruceChar.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Subskrypcja powiadomień błędna: ${e.message}")
                    }
                }

                notifyServicesDiscovered(gatt.services)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: byteArrayOf()
                dispatchData(characteristic.uuid, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: byteArrayOf()
            dispatchData(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun dispatchData(uuid: UUID, bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        listeners.forEach { it.onDataReceived(uuid, text, hex, bytes) }

        // Przekaż bezwzględnie strumień danych do mostka radiowego RF
        val freq = 433920000L
        BruceBleRadioBridge.processIncomingBleData(freq, text, bytes)
    }

    private fun notifyConnectionState(connected: Boolean, name: String?) {
        handler.post { listeners.forEach { it.onConnectionStateChanged(connected, name) } }
    }

    private fun notifyServicesDiscovered(services: List<BluetoothGattService>) {
        handler.post { listeners.forEach { it.onServicesDiscovered(services) } }
    }

    private fun notifyError(msg: String) {
        handler.post { listeners.forEach { it.onError(msg) } }
    }
}
