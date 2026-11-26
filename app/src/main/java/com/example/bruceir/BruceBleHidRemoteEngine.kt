package com.example.bruceir

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.*
import java.util.concurrent.Executors

object BruceBleHidRemoteEngine {

    private const val TAG = "BruceBleHidEngine"

    // UUIDs dla Standardowego HID Over GATT (HOGP)
    val HID_SERVICE_UUID: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    val HID_INFORMATION_UUID: UUID = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
    val REPORT_MAP_UUID: UUID = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
    val PROTOCOL_MODE_UUID: UUID = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
    val HID_CONTROL_POINT_UUID: UUID = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
    val REPORT_UUID: UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val REPORT_REFERENCE_UUID: UUID = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val PNP_ID_UUID: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME_UUID: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    // Opisy kodów użycia Consumer Control (Pilot TV)
    object ConsumerKeys {
        const val POWER = 0x0030
        const val MUTE = 0x00E2
        const val VOL_UP = 0x00E9
        const val VOL_DOWN = 0x00EA
        const val MENU = 0x0040
        const val HOME = 0x0223
        const val BACK = 0x0224
        const val DPAD_UP = 0x0042
        const val DPAD_DOWN = 0x0043
        const val DPAD_LEFT = 0x0044
        const val DPAD_RIGHT = 0x0045
        const val SELECT = 0x0041
        const val PLAY_PAUSE = 0x00CD
        const val NEXT_TRACK = 0x00B5
        const val PREV_TRACK = 0x00B6
        const val STOP = 0x00B7
        const val CHANNEL_UP = 0x009C
        const val CHANNEL_DOWN = 0x009D
        const val NETFLIX = 0x0182
        const val YOUTUBE = 0x0196
    }

    // HID Report Map Descriptor (Consumer Control ID=1, Mouse ID=2)
    private val HID_REPORT_MAP = byteArrayOf(
        // Consumer Control (Report ID 1) - Volume, D-Pad, Power, Media, Apps
        0x05.toByte(), 0x0C.toByte(), // USAGE_PAGE (Consumer)
        0x09.toByte(), 0x01.toByte(), // USAGE (Consumer Control)
        0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        0x85.toByte(), 0x01.toByte(), //   REPORT_ID (1)
        0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
        0x26.toByte(), 0xFF.toByte(), 0x03.toByte(), // LOGICAL_MAXIMUM (1023)
        0x19.toByte(), 0x00.toByte(), //   USAGE_MINIMUM (0)
        0x2A.toByte(), 0xFF.toByte(), 0x03.toByte(), // USAGE_MAXIMUM (1023)
        0x75.toByte(), 0x10.toByte(), //   REPORT_SIZE (16 bits)
        0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
        0x81.toByte(), 0x00.toByte(), //   INPUT (Data,Ary,Abs)
        0xC0.toByte(),                // END_COLLECTION

        // Mouse (Report ID 2) - Buttons (Left/Right), X, Y displacement
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
        0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        0x85.toByte(), 0x02.toByte(), //   REPORT_ID (2)
        0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
        0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
        0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs)
        0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
        0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
        0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs)
        0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     USAGE (X)
        0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
        0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
        0x95.toByte(), 0x02.toByte(), //     REPORT_COUNT (2)
        0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
        0xC0.toByte(),                //   END_COLLECTION
        0xC0.toByte()                 // END_COLLECTION
    )

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var inputReportConsumerChar: BluetoothGattCharacteristic? = null
    private var inputReportMouseChar: BluetoothGattCharacteristic? = null

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var isHidAppRegistered = false

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private var isPairingReceiverRegistered = false
    private var activeContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor()

    var isServerRunning = false
        private set

    var isAdvertising = false
        private set

    interface StateListener {
        fun onServerStateChanged(running: Boolean, statusText: String)
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected(device: BluetoothDevice)
        fun onPairingEvent(message: String)
    }

    private val listeners = mutableListOf<StateListener>()

    fun registerStateListener(listener: StateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregisterStateListener(listener: StateListener) {
        listeners.remove(listener)
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, 0)
                    Log.d(TAG, "Pairing request from ${device?.name ?: device?.address}, variant=$variant")

                    try {
                        if (device != null) {
                            if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION || variant == 3) {
                                device.setPairingConfirmation(true)
                                notifyPairingEvent("Auto-zaakceptowano kod parowania z ${device.name ?: device.address}")
                            } else if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                                device.setPin("0000".toByteArray())
                                notifyPairingEvent("Wysłano domyślny PIN 0000 do ${device.name ?: device.address}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Błąd automatycznej obsługi parowania: ${e.message}")
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBond = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

                    val name = device?.name ?: device?.address ?: "Urządzenie"
                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> notifyPairingEvent("⏳ Trwa parowanie z $name...")
                        BluetoothDevice.BOND_BONDED -> notifyPairingEvent("🟢 SPAROWANO POMYŚLNIE z $name! Pilot jest gotowy.")
                        BluetoothDevice.BOND_NONE -> {
                            if (prevBond == BluetoothDevice.BOND_BONDING) {
                                notifyPairingEvent("🔴 Parowanie z $name zostało odrzucone lub anulowane.")
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRemoteServer(context: Context, customDeviceName: String = "Bruce Smart Remote"): Boolean {
        val appContext = context.applicationContext
        activeContext = appContext
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = bluetoothManager.adapter ?: return false

        if (!adapter.isEnabled) {
            notifyServerState(false, "Bluetooth jest wyłączony w telefonie!")
            return false
        }

        registerPairingReceiver(appContext)

        try {
            adapter.name = customDeviceName
        } catch (_: Exception) {}

        // Inicjalizacja oficjalnego profilu Bluetooth HID Device (Android 9+)
        initNativeHidDeviceProxy(appContext, adapter, customDeviceName)

        // Otwarcie serwera GATT Server (jako zapasowy silnik BLE HOGP)
        gattServer = bluetoothManager.openGattServer(appContext, gattServerCallback)
        if (gattServer != null) {
            setupGattServices()
            startAdvertising(adapter, customDeviceName)
        }

        isServerRunning = true
        notifyServerState(true, "🟢 Pilot Bluetooth Aktywny (Natywny HID + BLE HOGP)! Wyszukaj w TV.")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun initNativeHidDeviceProxy(context: Context, adapter: BluetoothAdapter, deviceName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        bluetoothHidDevice = proxy as? BluetoothHidDevice
                        Log.d(TAG, "Uzyskano natywny profil BluetoothHidDevice!")
                        registerNativeHidApp(deviceName)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        bluetoothHidDevice = null
                        isHidAppRegistered = false
                    }
                }
            }, BluetoothProfile.HID_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerNativeHidApp(deviceName: String) {
        val hidDev = bluetoothHidDevice ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                deviceName,
                "Universal Bluetooth Remote Control",
                "Bruce Security Labs",
                0x80.toByte(), // Combo / Remote Control Subclass
                HID_REPORT_MAP
            )

            try {
                hidDev.registerApp(sdpSettings, null, null, executor, object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                        isHidAppRegistered = registered
                        Log.d(TAG, "Natywna aplikacja Bluetooth HID zarejestrowana: $registered")
                        if (registered) {
                            notifyPairingEvent("🟢 Rejestracja Natywnego Profilu HID pomyślna! TV widzi telefon jako fabryczny pilot.")
                        }
                    }

                    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                        if (device != null) {
                            if (state == BluetoothProfile.STATE_CONNECTED) {
                                connectedDevices.add(device)
                                notifyDeviceConnected(device)
                                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                                    try {
                                        device.createBond()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Auto-bond error: ${e.message}")
                                    }
                                }
                            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                connectedDevices.remove(device)
                                notifyDeviceDisconnected(device)
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Błąd rejestracji BluetoothHidDevice: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopRemoteServer() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
        advertiser = null
        isAdvertising = false

        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && bluetoothHidDevice != null) {
            try {
                bluetoothHidDevice?.unregisterApp()
            } catch (_: Exception) {}
            bluetoothHidDevice = null
            isHidAppRegistered = false
        }

        isServerRunning = false
        connectedDevices.clear()

        activeContext?.let { unregisterPairingReceiver(it) }
        notifyServerState(false, "⏹ Serwer Pilota Bluetooth Zatrzymany")
    }

    @SuppressLint("MissingPermission")
    fun sendConsumerKey(usageCode: Int): Boolean {
        val keyDownPayload = byteArrayOf(usageCode.toByte(), (usageCode ushr 8).toByte())
        var sentAny = false

        // 1. Spróbuj wysłać przez Natywny BluetoothHidDevice API (Android 9+)
        val hidDev = bluetoothHidDevice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDev != null && connectedDevices.isNotEmpty()) {
            connectedDevices.forEach { device ->
                val ok = hidDev.sendReport(device, 1, keyDownPayload)
                if (ok) sentAny = true
            }
            handler.postDelayed({
                val keyUpPayload = byteArrayOf(0x00, 0x00)
                connectedDevices.forEach { device ->
                    hidDev.sendReport(device, 1, keyUpPayload)
                }
            }, 50)
            if (sentAny) return true
        }

        // 2. Spróbuj wysłać przez powiadomienie BLE GATT Server
        val server = gattServer
        val char = inputReportConsumerChar
        if (server != null && char != null && connectedDevices.isNotEmpty()) {
            connectedDevices.forEach { device ->
                if (sendNotification(server, device, char, keyDownPayload)) sentAny = true
            }
            handler.postDelayed({
                val keyUpPayload = byteArrayOf(0x00, 0x00)
                connectedDevices.forEach { device ->
                    sendNotification(server, device, char, keyUpPayload)
                }
            }, 50)
        }

        return sentAny
    }

    @SuppressLint("MissingPermission")
    fun sendMouseMove(dx: Int, dy: Int, leftClick: Boolean = false, rightClick: Boolean = false): Boolean {
        var buttons = 0
        if (leftClick) buttons = buttons or 0x01
        if (rightClick) buttons = buttons or 0x02

        val clampDx = dx.coerceIn(-127, 127).toByte()
        val clampDy = dy.coerceIn(-127, 127).toByte()
        val mousePayload = byteArrayOf(buttons.toByte(), clampDx, clampDy)
        var sentAny = false

        val hidDev = bluetoothHidDevice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDev != null && connectedDevices.isNotEmpty()) {
            connectedDevices.forEach { device ->
                if (hidDev.sendReport(device, 2, mousePayload)) sentAny = true
            }
            if (sentAny) return true
        }

        val server = gattServer
        val char = inputReportMouseChar
        if (server != null && char != null && connectedDevices.isNotEmpty()) {
            connectedDevices.forEach { device ->
                if (sendNotification(server, device, char, mousePayload)) sentAny = true
            }
        }
        return sentAny
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(server: BluetoothGattServer, device: BluetoothDevice, char: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, char, false, payload) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, char, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification error to ${device.address}: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServices() {
        val server = gattServer ?: return

        // 1. Device Information Service (0x180A)
        val devInfoService = BluetoothGattService(DEVICE_INFO_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val mfrChar = BluetoothGattCharacteristic(MANUFACTURER_NAME_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        mfrChar.value = "Bruce Security Labs".toByteArray()
        devInfoService.addCharacteristic(mfrChar)

        val pnpChar = BluetoothGattCharacteristic(PNP_ID_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        pnpChar.value = byteArrayOf(0x02, 0x05, 0x0E, 0x00, 0x01, 0x00, 0x01)
        devInfoService.addCharacteristic(pnpChar)

        server.addService(devInfoService)

        // 2. Battery Service (0x180F)
        val batteryService = BluetoothGattService(BATTERY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val batteryLevelChar = BluetoothGattCharacteristic(BATTERY_LEVEL_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        batteryLevelChar.value = byteArrayOf(100)
        batteryService.addCharacteristic(batteryLevelChar)
        server.addService(batteryService)

        // 3. Human Interface Device Service (0x1812)
        val hidService = BluetoothGattService(HID_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // HID Information Characteristic (0x2A4A)
        val hidInfoChar = BluetoothGattCharacteristic(HID_INFORMATION_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        hidInfoChar.value = byteArrayOf(0x01, 0x01, 0x00, 0x02)
        hidService.addCharacteristic(hidInfoChar)

        // HID Report Map Characteristic (0x2A4B)
        val reportMapChar = BluetoothGattCharacteristic(REPORT_MAP_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        reportMapChar.value = HID_REPORT_MAP
        hidService.addCharacteristic(reportMapChar)

        // Protocol Mode Characteristic (0x2A4E)
        val protocolModeChar = BluetoothGattCharacteristic(PROTOCOL_MODE_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        @Suppress("DEPRECATION")
        protocolModeChar.value = byteArrayOf(0x01)
        hidService.addCharacteristic(protocolModeChar)

        // HID Control Point (0x2A4C)
        val controlPointChar = BluetoothGattCharacteristic(HID_CONTROL_POINT_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        hidService.addCharacteristic(controlPointChar)

        // HID Consumer Input Report (0x2A4D - Report ID 1)
        val consumerChar = BluetoothGattCharacteristic(
            REPORT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val consumerRefDesc = BluetoothGattDescriptor(REPORT_REFERENCE_UUID, BluetoothGattDescriptor.PERMISSION_READ)
        @Suppress("DEPRECATION")
        consumerRefDesc.value = byteArrayOf(0x01, 0x01)
        consumerChar.addDescriptor(consumerRefDesc)

        val consumerCccdDesc = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        @Suppress("DEPRECATION")
        consumerCccdDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        consumerChar.addDescriptor(consumerCccdDesc)

        hidService.addCharacteristic(consumerChar)
        inputReportConsumerChar = consumerChar

        // HID Mouse Input Report (0x2A4D - Report ID 2)
        val mouseChar = BluetoothGattCharacteristic(
            REPORT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val mouseRefDesc = BluetoothGattDescriptor(REPORT_REFERENCE_UUID, BluetoothGattDescriptor.PERMISSION_READ)
        @Suppress("DEPRECATION")
        mouseRefDesc.value = byteArrayOf(0x02, 0x01)
        mouseChar.addDescriptor(mouseRefDesc)

        val mouseCccdDesc = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        @Suppress("DEPRECATION")
        mouseCccdDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        mouseChar.addDescriptor(mouseCccdDesc)

        hidService.addCharacteristic(mouseChar)
        inputReportMouseChar = mouseChar

        server.addService(hidService)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter, deviceName: String) {
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(HID_SERVICE_UUID))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Błąd startu nadawania BLE Advertiser: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.d(TAG, "BLE HID Advertiser uruchomiony pomyślnie w eterze!")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Start BLE Advertiser nie powiódł się (kod: $errorCode)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceName = device.name ?: device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "TV / Klient połączony do serwera GATT: $deviceName")
                connectedDevices.add(device)
                notifyDeviceConnected(device)
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    try {
                        device.createBond()
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-bond error: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "TV / Klient rozłączony od serwera GATT: $deviceName")
                connectedDevices.remove(device)
                notifyDeviceDisconnected(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: byteArrayOf()
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            @Suppress("DEPRECATION")
            val value = descriptor.value ?: byteArrayOf()
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            @Suppress("DEPRECATION")
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private fun registerPairingReceiver(context: Context) {
        if (!isPairingReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            context.registerReceiver(pairingReceiver, filter)
            isPairingReceiverRegistered = true
        }
    }

    private fun unregisterPairingReceiver(context: Context) {
        if (isPairingReceiverRegistered) {
            try {
                context.unregisterReceiver(pairingReceiver)
            } catch (_: Exception) {}
            isPairingReceiverRegistered = false
        }
    }

    private fun notifyServerState(running: Boolean, text: String) {
        handler.post { listeners.forEach { it.onServerStateChanged(running, text) } }
    }

    private fun notifyDeviceConnected(device: BluetoothDevice) {
        handler.post { listeners.forEach { it.onDeviceConnected(device) } }
    }

    private fun notifyDeviceDisconnected(device: BluetoothDevice) {
        handler.post { listeners.forEach { it.onDeviceDisconnected(device) } }
    }

    private fun notifyPairingEvent(message: String) {
        handler.post { listeners.forEach { it.onPairingEvent(message) } }
    }
}
