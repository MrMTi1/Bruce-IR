package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
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
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.view.View
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bruceir.ui.BruceScreenView
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONException
import org.json.JSONObject

class RemoteConsoleActivity : AppCompatActivity() {

    private lateinit var bruceMenu: TextView
    private lateinit var mirrorStatus: TextView
    private lateinit var connectionStatus: View
    private lateinit var menuScroll: ScrollView
    private lateinit var screenMirrorView: BruceScreenView
    private lateinit var titleView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val commandQueue = ArrayDeque<String>()
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val incomingPackets = ConcurrentLinkedQueue<ByteArray>()
    private val outputDrainScheduled = AtomicBoolean(false)
    private val textBuffer = StringBuilder()
    private val commandOutput = ByteArrayOutputStream()
    private val mirrorWireBuffer = ByteArrayOutputStream()
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var serialCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var isReady = false
    private var writeInFlight = false
    private var currentCommand: String? = null
    private var menuResponseRendered = false
    private var lastMenuContentSignature: String? = null
    private var selectionMenuBaseline: String? = null
    private var menuRefreshRunnable: Runnable? = null
    private var invalidMenuRetryRunnable: Runnable? = null
    private var menuResponseWatchdog: Runnable? = null
    private var inactiveMenuRefreshRunnable: Runnable? = null
    private var waitingForSelectionRefresh = false
    private var selectionMenuTimedOut = false
    private var menuRefreshAttempts = 0
    private var invalidMenuRetryAttempts = 0
    private var maxWriteBytes = 20
    private var lastMenuText = ""
    private var lastNavigationCommandAt = 0L
    private var commandStartRunnable: Runnable? = null
    private var moduleCommandTitle: String? = null
    private var moduleCommandText: String? = null
    private var pendingShowModules = false
    private var screenMirrorActive = false
    private var closeAfterMirrorStop = false
    private var wifiScanReceiver: BroadcastReceiver? = null
    private var wifiScanTimeout: Runnable? = null
    private var wifiAutoRefreshRunnable: Runnable? = null
    private var wifiAutoRefreshIntervalMs: Long = 0L
    private var selectedWifiBssid: String? = null
    private var lastWifiScanResults: List<android.net.wifi.ScanResult> = emptyList()
    private var wifiTargetDialog: AlertDialog? = null
    private var wifiDetailDialog: AlertDialog? = null
    private var waitingForWifiScanPermission = false
    private var pendingRfExport = ""
    private var pendingBleDownload = ""
    private val recentRfCaptures = mutableListOf<Map<String, String>>()
    private var pendingBleUploadFolder = "/"
    private var pendingBleUploadPath: String? = null
    private var pendingBleUploadBytes: ByteArray? = null
    private var bleUploadAwaitingInput = false
    private var bleUploadSending = false
    private var bleUploadTimeoutRunnable: Runnable? = null

    private val bleUploadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareBleTextUpload(uri, pendingBleUploadFolder)
    }

    private val bleDownloadPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(pendingBleDownload.toByteArray(StandardCharsets.UTF_8))
            } ?: error("Could not open the selected destination")
        }.onSuccess {
            Toast.makeText(this, "Bruce text file saved to phone.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not save file: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val rfExportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(pendingRfExport.toByteArray(StandardCharsets.UTF_8))
            } ?: error("Could not open the selected destination")
        }.onSuccess {
            Toast.makeText(this, "Sub-GHz capture saved.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not save capture: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val scanTimeout = Runnable {
        if (isScanning) {
            stopScan()
            setConnectionState(false, "Bruce not found. Enable Config > Toggle BLE API.")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (requiredPermissions().all { grants[it] == true || hasPermission(it) }) startScan()
        else setConnectionState(false, "Bluetooth permission is required to connect.")
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startScan()
        else setConnectionState(false, "Bluetooth is disabled.")
    }

    private val wifiLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && waitingForWifiScanPermission) {
            waitingForWifiScanPermission = false
            scanPhoneWifiTargets()
        } else {
            waitingForWifiScanPermission = false
            showWifiTargetsMessage("Location permission is required by Android to scan nearby Wi-Fi networks.")
        }
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
                setConnectionState(false, "Bluetooth scan failed ($errorCode).")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    if (this@RemoteConsoleActivity.gatt === gatt) {
                        clearConnection(gatt)
                        setConnectionState(false, "Bluetooth connection failed ($status).")
                    }
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                    runOnUiThread { setConnectionState(false, "Connected; discovering Bruce services…") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> runOnUiThread {
                    if (this@RemoteConsoleActivity.gatt === gatt) {
                        clearConnection(gatt)
                        setConnectionState(false, "Disconnected.")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxWriteBytes = (mtu - 3).coerceAtLeast(20)
            serialCharacteristic?.let { enableNotifications(gatt, it) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { setConnectionState(false, "Could not discover Bruce BLE services.") }
                return
            }
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                runOnUiThread { setConnectionState(false, "Bruce BLE serial characteristic not found.") }
                return
            }
            serialCharacteristic = characteristic
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                runOnUiThread { setConnectionState(false, "Could not enable BLE notifications.") }
                return
            }
            if (gatt.requestMtu(DESIRED_BLE_MTU)) return
            enableNotifications(gatt, characteristic)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                runOnUiThread { setConnectionState(false, "Bruce BLE notification descriptor not found.") }
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
                runOnUiThread { setConnectionState(false, "Could not subscribe to Bruce output.") }
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
                    setConnectionState(true, "Connected; loading Bruce menu…")
                    enqueueCommand(MENU_COMMAND)
                } else {
                    setConnectionState(false, "Could not subscribe to Bruce output ($status).")
                }
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
                    failCurrentCommand("Bluetooth command write failed ($status).")
                    return@runOnUiThread
                }
                writeNextChunk()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.BRUCE)) return
        setContentView(R.layout.activity_remote_console)
        bruceMenu = findViewById(R.id.tvBruceMenu)
        mirrorStatus = findViewById(R.id.tvMirrorStatus)
        connectionStatus = findViewById(R.id.ivConnectionStatus)
        menuScroll = findViewById(R.id.menuScroll)
        screenMirrorView = findViewById(R.id.bruceScreenMirror)
        titleView = findViewById(R.id.tvTitle)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        findViewById<ImageButton>(R.id.btnRemoteBack).setOnClickListener { closeRemoteConsole() }
        findViewById<ImageButton>(R.id.btnMenuRefresh).setOnClickListener { requestMenuRefresh() }
        findViewById<View>(R.id.btnBleFiles).setOnClickListener { requestFileListing("/") }
        findViewById<View>(R.id.btnBleModules).setOnClickListener { showModules() }
        setupNavButton(R.id.btnNavUp, "up")
        setupNavButton(R.id.btnNavDown, "down")
        setupNavButton(R.id.btnNavSel, "sel")
        setupNavButton(R.id.btnNavBack, "esc")
        setupNavButton(R.id.btnNavNext, "next")

        setConnectionState(false, "Connect to Bruce BLE; Wi-Fi is not used.")
        startScan()

        if (intent.getBooleanExtra("show_modules", false)) {
            pendingShowModules = true
        } else if (intent.getBooleanExtra("start_wifi_targets", false)) {
            handler.postDelayed({ requestPhoneWifiTargets() }, 600)
        }
    }

    private fun setupNavButton(id: Int, action: String) {
        val button = findViewById<ImageButton>(id)
        button.setOnClickListener { view ->
            animateNavButton(view)
            cancelInactiveMenuRefresh()
            if (isReady) {
                setConnectionState(
                    true,
                    if (action == "sel") "Opening selected Bruce item…"
                    else "Updating Bruce menu…"
                )
            }
            enqueueCommand("nav $action")
        }
        if (action == "sel") {
            button.setOnLongClickListener { view ->
                animateNavButton(view)
                cancelInactiveMenuRefresh()
                if (isReady) setConnectionState(true, "Going back in Bruce menu…")
                enqueueCommand("nav esc")
                true
            }
            button.contentDescription = "Select; long press for back"
        }
    }

    private fun animateNavButton(view: View) {
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(50).start()
        }.start()
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
            setConnectionState(false, "This phone does not support Bluetooth.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            setConnectionState(false, "Bluetooth LE scanning is unavailable.")
            return
        }
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            setConnectionState(false, "Looking for Bruce BLE…")
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (_: SecurityException) {
            setConnectionState(false, "Bluetooth permission was denied.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (isScanning) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                setConnectionState(false, "Bluetooth permission was denied.")
            }
        }
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (requiredPermissions().any { !hasPermission(it) }) {
            setConnectionState(false, "Bluetooth permission was denied.")
            return
        }
        try {
            setConnectionState(false, "Connecting to Bruce…")
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            setConnectionState(false, "Bluetooth permission was denied.")
        }
    }

    private fun enqueueCommand(command: String) {
        if (!isReady) return
        if (command.toByteArray(StandardCharsets.UTF_8).size > MAX_COMMAND_BYTES) {
            setConnectionState(false, "Command is too long.")
            return
        }
        commandQueue.addLast(command)
        beginNextCommand()
    }

    private fun beginNextCommand() {
        if (!isReady || currentCommand != null) return
        val nextCommand = commandQueue.peekFirst() ?: return
        if (nextCommand.startsWith("nav ")) {
            val delay = NAVIGATION_COMMAND_INTERVAL_MS - (SystemClock.uptimeMillis() - lastNavigationCommandAt)
            if (delay > 0) {
                commandStartRunnable?.let(handler::removeCallbacks)
                val runnable = Runnable {
                    commandStartRunnable = null
                    beginNextCommand()
                }
                commandStartRunnable = runnable
                handler.postDelayed(runnable, delay)
                return
            }
            lastNavigationCommandAt = SystemClock.uptimeMillis()
        }
        currentCommand = commandQueue.pollFirst() ?: return
        commandOutput.reset()
        menuResponseRendered = false
        if (currentCommand == MENU_COMMAND) scheduleMenuResponseWatchdog(MENU_RESPONSE_START_TIMEOUT_MS)
        pendingWrites.clear()
        val bytes = (currentCommand + "\n").toByteArray(StandardCharsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + maxWriteBytes).coerceAtMost(bytes.size)
            pendingWrites.addLast(bytes.copyOfRange(offset, end))
            offset = end
        }
        writeNextChunk()
    }

    @SuppressLint("MissingPermission")
    private fun writeNextChunk() {
        if (writeInFlight || !isReady) return
        val chunk = pendingWrites.pollFirst()
        if (chunk == null) {
            if (bleUploadSending) {
                bleUploadSending = false
                if (textBuffer.endsWith("# ")) onCommandPrompt()
            }
            return
        }
        val currentGatt = gatt
        val characteristic = serialCharacteristic
        if (currentGatt == null || characteristic == null) {
            failCurrentCommand("Bluetooth connection is no longer ready.")
            return
        }
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
        if (started) writeInFlight = true
        else failCurrentCommand("Could not send Bluetooth command.")
    }

    private fun failCurrentCommand(message: String) {
        val failedCommand = currentCommand
        clearBleUploadTimeout()
        pendingBleUploadPath = null
        pendingBleUploadBytes = null
        bleUploadAwaitingInput = false
        bleUploadSending = false
        pendingWrites.clear()
        commandQueue.clear()
        currentCommand = null
        menuResponseWatchdog?.let(handler::removeCallbacks)
        menuResponseWatchdog = null
        writeInFlight = false
        if (failedCommand == "display start") {
            screenMirrorActive = false
            mirrorWireBuffer.reset()
            setScreenMirrorVisible(false)
        }
        moduleCommandTitle = null
        moduleCommandText = null
        closeAfterMirrorStop = false
        fileListingCallback = null
        fileReadCallback = null
        fileOperationCallback = null
        setConnectionState(false, message)
    }

    private fun receiveOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        incomingPackets.add(bytes.copyOf())
        if (outputDrainScheduled.compareAndSet(false, true)) {
            handler.post(::drainIncomingPackets)
        }
    }

    private fun drainIncomingPackets() {
        while (true) {
            val packet = incomingPackets.poll() ?: break
            if (screenMirrorActive) consumeMirrorStream(packet)
            else processTextOutput(packet)
            if (currentCommand == MENU_COMMAND) scheduleMenuResponseWatchdog(MENU_RESPONSE_IDLE_TIMEOUT_MS)
        }
        outputDrainScheduled.set(false)
        if (incomingPackets.isNotEmpty() && outputDrainScheduled.compareAndSet(false, true)) {
            handler.post(::drainIncomingPackets)
        }
    }

    private fun processTextOutput(bytes: ByteArray) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            val character = value.toChar()
            textBuffer.append(character)
            commandOutput.write(value)
            if (textBuffer.length > MAX_TEXT_BUFFER) textBuffer.delete(0, textBuffer.length - MAX_TEXT_BUFFER)
            if (commandOutput.size() > MAX_COMMAND_OUTPUT * 2) {
                val retained = commandOutput.toByteArray().takeLast(MAX_COMMAND_OUTPUT).toByteArray()
                commandOutput.reset()
                commandOutput.write(retained)
            }
            if (currentCommand == MENU_COMMAND && character == '\n') {
                renderCompleteMenuResponse()
            }
            if (bleUploadAwaitingInput && textBuffer.contains(BLE_UPLOAD_READY_MARKER)) {
                queueBleUploadPayload()
            }
            if (!bleUploadSending && textBuffer.endsWith("# ")) onCommandPrompt()
        }
    }

    private fun consumeMirrorStream(bytes: ByteArray) {
        val pending = mirrorWireBuffer.toByteArray() + bytes
        mirrorWireBuffer.reset()
        var offset = 0
        var textStart = 0

        while (offset < pending.size) {
            if ((pending[offset].toInt() and 0xFF) != DISPLAY_PACKET_HEADER) {
                offset++
                continue
            }

            if (offset > textStart) {
                processTextOutput(pending.copyOfRange(textStart, offset))
            }
            if (offset + 1 >= pending.size) {
                mirrorWireBuffer.write(pending, offset, pending.size - offset)
                return
            }

            val packetSize = pending[offset + 1].toInt() and 0xFF
            if (packetSize < 3) {
                processTextOutput(byteArrayOf(pending[offset]))
                offset++
                textStart = offset
                continue
            }
            if (offset + packetSize > pending.size) {
                mirrorWireBuffer.write(pending, offset, pending.size - offset)
                return
            }

            screenMirrorView.appendScreenPacket(pending.copyOfRange(offset, offset + packetSize))
            offset += packetSize
            textStart = offset
        }

        if (textStart < pending.size) {
            processTextOutput(pending.copyOfRange(textStart, pending.size))
        }
    }

    private fun renderCompleteMenuResponse() {
        if (menuResponseRendered) return
        val response = String(commandOutput.toByteArray(), StandardCharsets.UTF_8)
        val menuJson = extractMenuJson(response) ?: return
        menuResponseRendered = showBruceMenu(menuJson)
    }

    private fun extractMenuJson(response: String): String? {
        var candidateStart = response.indexOf('{')
        while (candidateStart >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (index in candidateStart until response.length) {
                val character = response[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                    continue
                }
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = response.substring(candidateStart, index + 1)
                            if (isMenuJson(candidate)) return candidate
                            break
                        }
                    }
                }
            }
            candidateStart = response.indexOf('{', candidateStart + 1)
        }
        return null
    }

    private fun isMenuJson(candidate: String): Boolean = try {
        JSONObject(candidate).getJSONArray("options")
        true
    } catch (_: JSONException) {
        false
    }

    private fun onCommandPrompt() {
        menuResponseWatchdog?.let(handler::removeCallbacks)
        menuResponseWatchdog = null
        textBuffer.clear()
        val completedCommand = currentCommand
        val output = String(commandOutput.toByteArray(), StandardCharsets.UTF_8)
            .removeSuffix("# ")
            .trimEnd('\r', '\n')
        commandOutput.reset()
        currentCommand = null
        if (completedCommand?.startsWith("storage write ") == true) {
            clearBleUploadTimeout()
            val uploadedPath = pendingBleUploadPath
            pendingBleUploadPath = null
            pendingBleUploadBytes = null
            bleUploadAwaitingInput = false
            bleUploadSending = false
            if (output.contains("File written:", ignoreCase = true)) {
                setConnectionState(true, "File uploaded over BLE: ${uploadedPath ?: "Bruce storage"}")
                Toast.makeText(this, "File sent to Bruce over Bluetooth.", Toast.LENGTH_LONG).show()
            } else {
                setConnectionState(false, "Bruce did not confirm the BLE file write.")
                Toast.makeText(this, output.ifBlank { "Bruce did not confirm the file write." }, Toast.LENGTH_LONG).show()
            }
        }
        when {
            completedCommand == MENU_COMMAND && !menuResponseRendered -> {
                val menuJson = extractMenuJson(output)
                if (menuJson == null || !showBruceMenu(menuJson)) {
                    if (waitingForSelectionRefresh &&
                        menuRefreshAttempts < MAX_MENU_REFRESH_ATTEMPTS
                    ) {
                        menuRefreshAttempts++
                        bruceMenu.text = "Loading Bruce menu and results..."
                        setConnectionState(true, "Waiting for Bruce menu and results…")
                        scheduleMenuRefresh(MENU_REFRESH_INTERVAL_MS)
                    } else if (invalidMenuRetryAttempts < MAX_INVALID_MENU_RETRIES) {
                        invalidMenuRetryAttempts++
                        setConnectionState(
                            true,
                            "Menu response was incomplete; retrying (${invalidMenuRetryAttempts}/$MAX_INVALID_MENU_RETRIES)…"
                        )
                        scheduleInvalidMenuRetry()
                    } else {
                        waitingForSelectionRefresh = false
                        showInvalidMenuResponse(output)
                    }
                } else {
                    menuResponseRendered = true
                }
            }
            completedCommand?.startsWith("nav ") == true -> {
                val anotherNavigationQueued = commandQueue.peekFirst()?.startsWith("nav ") == true
                val menuRefreshQueued = commandQueue.contains(MENU_COMMAND)
                if (!screenMirrorActive && !anotherNavigationQueued && !menuRefreshQueued) {
                    waitingForSelectionRefresh = true
                    selectionMenuTimedOut = false
                    selectionMenuBaseline = lastMenuContentSignature
                    menuRefreshAttempts = 0
                    if (completedCommand == "nav sel") {
                        bruceMenu.text = "Loading selected Bruce menu..."
                        scheduleMenuRefresh(MENU_INITIAL_REFRESH_DELAY_MS)
                    } else {
                        commandQueue.addFirst(MENU_COMMAND)
                    }
                }
            }
        }
        fileListingCallback?.let { callback ->
            if (completedCommand?.startsWith("ls ") == true) {
                fileListingCallback = null
                callback(output)
            }
        }
        fileReadCallback?.let { callback ->
            if (completedCommand?.startsWith("cat ") == true) {
                fileReadCallback = null
                callback(output)
            }
        }
        fileOperationCallback?.let { callback ->
            if (completedCommand?.startsWith("mkdir ") == true ||
                completedCommand?.startsWith("rm ") == true ||
                completedCommand?.startsWith("rmdir ") == true
            ) {
                fileOperationCallback = null
                callback(output)
            }
        }
        if (moduleCommandText != null && completedCommand == moduleCommandText) {
            val completedTitle = moduleCommandTitle ?: "Bruce module"
            moduleCommandText = null
            moduleCommandTitle = null
            when (completedCommand) {
                "display start" -> {
                    if (output.contains("Could not start async serial", ignoreCase = true)) {
                        screenMirrorActive = false
                        mirrorWireBuffer.reset()
                        setScreenMirrorVisible(false)
                        setConnectionState(false, "Bruce could not start screen mirroring.")
                    } else {
                        setConnectionState(true, "Experimental screen mirror active.")
                    }
                }
                "display stop" -> {
                    screenMirrorActive = false
                    mirrorWireBuffer.reset()
                    setScreenMirrorVisible(false)
                    setConnectionState(true, "Screen mirror stopped.")
                    if (closeAfterMirrorStop) {
                        closeAfterMirrorStop = false
                        finish()
                        return
                    }
                    commandQueue.addFirst(MENU_COMMAND)
                }
                else -> {
                    if (completedCommand == "rf rx" || completedCommand == "rf rx raw") {
                        showRfCaptureAnalysis(output)
                    } else {
                        bruceMenu.text = "$completedTitle\n\n${output.ifBlank { "(No output)" }}"
                    }
                    setConnectionState(true, "Module command complete.")
                }
            }
        }
        if (pendingShowModules && isReady && currentCommand == null && commandQueue.isEmpty()) {
            pendingShowModules = false
            handler.post { showModules() }
        }
        beginNextCommand()
    }

    private fun showBruceMenu(response: String): Boolean {
        try {
            val menu = JSONObject(response)
            val title = menu.optString("menu_title").ifBlank { "Bruce menu" }
            val options = menu.getJSONArray("options")
            val active = menu.optInt("active", -1)
            val menuActive = menu.optBoolean("menu_active", true)
            val contentSignature = buildString {
                append(title)
                append('\n')
                append(menuActive)
                append('\n')
                append(menu.optString("menu", ""))
                append('\n')
                append(active)
                append('\n')
                for (index in 0 until options.length()) {
                    val item = options.get(index)
                    append(if (item is JSONObject) item.optString("label", "(unnamed)") else item.toString())
                    append('\n')
                }
            }
            invalidMenuRetryAttempts = 0
            invalidMenuRetryRunnable?.let(handler::removeCallbacks)
            invalidMenuRetryRunnable = null
            if (menuActive) cancelInactiveMenuRefresh()
            if (waitingForSelectionRefresh) {
                if (contentSignature != selectionMenuBaseline) {
                    waitingForSelectionRefresh = false
                    selectionMenuTimedOut = false
                    selectionMenuBaseline = null
                    menuRefreshRunnable?.let(handler::removeCallbacks)
                    menuRefreshRunnable = null
                } else {
                    menuRefreshAttempts++
                    if (menuRefreshAttempts < MAX_MENU_REFRESH_ATTEMPTS) {
                        scheduleMenuRefresh(MENU_REFRESH_INTERVAL_MS)
                        bruceMenu.text = "Loading Bruce menu and results..."
                        setConnectionState(true, "Waiting for Bruce menu and results…")
                        return true
                    } else {
                        waitingForSelectionRefresh = false
                        selectionMenuTimedOut = true
                        selectionMenuBaseline = null
                        menuRefreshRunnable?.let(handler::removeCallbacks)
                        menuRefreshRunnable = null
                    }
                }
            }
            if (!menuActive) {
                lastMenuContentSignature = contentSignature
                bruceMenu.text = "$title\n\nBruce is loading. Waiting for the menu…"
                setConnectionState(true, "Bruce is busy: $title")
                scheduleInactiveMenuRefresh()
                return true
            }
            cancelInactiveMenuRefresh()
            lastMenuContentSignature = contentSignature
            val text = buildString {
                appendLine(title)
                appendLine()
                var activeOffset = -1
                for (index in 0 until options.length()) {
                    val item = options.get(index)
                    val label = if (item is JSONObject) item.optString("label", "(unnamed)") else item.toString()
                    if (index == active) activeOffset = length
                    append(if (index == active) "> " else "  ")
                    appendLine(formatMenuOption(label))
                }
                if (options.length() == 0) appendLine("No menu options.")
                bruceMenu.post {
                    if (activeOffset < 0 || menuScroll.height <= 0) return@post
                    val layout = bruceMenu.layout ?: return@post
                    val activeLine = layout.getLineForOffset(activeOffset)
                    val selectedCenter =
                        (layout.getLineTop(activeLine) + layout.getLineBottom(activeLine)) / 2
                    val targetScroll = (selectedCenter - menuScroll.height / 2)
                        .coerceIn(0, (bruceMenu.height - menuScroll.height).coerceAtLeast(0))
                    menuScroll.smoothScrollTo(0, targetScroll)
                }
            }
            bruceMenu.text = text
            lastMenuText = text
            setConnectionState(
                true,
                "BLE text menu active."
            )
            return true
        } catch (exception: JSONException) {
            showInvalidMenuResponse(response, exception.message)
            return false
        }
    }

    private fun scheduleMenuRefresh(delayMs: Long) {
        menuRefreshRunnable?.let(handler::removeCallbacks)
        val refresh = Runnable {
            if (isReady && waitingForSelectionRefresh && currentCommand == null) {
                enqueueCommand(MENU_COMMAND)
            }
        }
        menuRefreshRunnable = refresh
        handler.postDelayed(refresh, delayMs)
    }

    private fun scheduleInactiveMenuRefresh() {
        cancelInactiveMenuRefresh()
        val refresh = Runnable {
            inactiveMenuRefreshRunnable = null
            if (!isReady || screenMirrorActive) return@Runnable
            if (currentCommand == null && commandQueue.isEmpty()) {
                enqueueCommand(MENU_COMMAND)
            } else if (currentCommand != MENU_COMMAND && !commandQueue.contains(MENU_COMMAND)) {
                scheduleInactiveMenuRefresh()
            }
        }
        inactiveMenuRefreshRunnable = refresh
        handler.postDelayed(refresh, INACTIVE_MENU_REFRESH_INTERVAL_MS)
    }

    private fun cancelInactiveMenuRefresh() {
        inactiveMenuRefreshRunnable?.let(handler::removeCallbacks)
        inactiveMenuRefreshRunnable = null
    }

    private fun scheduleMenuResponseWatchdog(delayMs: Long) {
        menuResponseWatchdog?.let(handler::removeCallbacks)
        if (currentCommand != MENU_COMMAND) {
            menuResponseWatchdog = null
            return
        }
        val watchdog = Runnable {
            menuResponseWatchdog = null
            if (currentCommand == MENU_COMMAND) onCommandPrompt()
        }
        menuResponseWatchdog = watchdog
        handler.postDelayed(watchdog, delayMs)
    }

    private fun scheduleInvalidMenuRetry() {
        invalidMenuRetryRunnable?.let(handler::removeCallbacks)
        val retry = Runnable {
            if (isReady && currentCommand == null) enqueueCommand(MENU_COMMAND)
        }
        invalidMenuRetryRunnable = retry
        handler.postDelayed(retry, INVALID_MENU_RETRY_DELAY_MS)
    }

    private fun requestMenuRefresh() {
        if (!isReady || screenMirrorActive) return
        cancelInactiveMenuRefresh()
        menuRefreshRunnable?.let(handler::removeCallbacks)
        menuRefreshRunnable = null
        invalidMenuRetryRunnable?.let(handler::removeCallbacks)
        invalidMenuRetryRunnable = null
        waitingForSelectionRefresh = false
        selectionMenuTimedOut = false
        selectionMenuBaseline = null
        menuRefreshAttempts = 0
        invalidMenuRetryAttempts = 0
        commandQueue.removeIf { it == MENU_COMMAND }
        commandQueue.addFirst(MENU_COMMAND)
        bruceMenu.text = "Loading Bruce menu and results..."
        setConnectionState(true, "Refreshing Bruce menu and results…")
        beginNextCommand()
    }

    private fun showInvalidMenuResponse(response: String, reason: String? = null) {
        val preview = response
            .filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
            .takeLast(MAX_MENU_ERROR_PREVIEW)
            .trim()
        bruceMenu.text = buildString {
            appendLine("Could not read the menu response.")
            if (!reason.isNullOrBlank()) appendLine(reason)
            if (preview.isNotEmpty()) {
                appendLine()
                append(preview)
            }
        }
        setConnectionState(false, "Bruce returned an invalid menu (${response.length} characters).")
    }

    private fun formatMenuOption(label: String): String {
        val match = WIFI_NETWORK_LABEL.matchEntire(label) ?: return label
        val ssid = match.groupValues[2].ifEmpty { "(hidden network)" }
        val security = if (match.groupValues[1] == "#") "Secured" else "Open"
        val rssi = match.groupValues[3]
        val channel = match.groupValues[5]
        return "$ssid\n    $security  |  $rssi dBm  |  Ch $channel"
    }

    private fun setConnectionState(connected: Boolean, message: String) {
        if (!::connectionStatus.isInitialized) return
        connectionStatus.setBackgroundResource(
            if (connected) R.drawable.connection_indicator_connected
            else R.drawable.connection_indicator_disconnected
        )
        mirrorStatus.text = message
        mirrorStatus.setTextColor(getColor(if (connected) R.color.accent_green else R.color.accent_red))
    }

    private fun showModules() {
        cancelInactiveMenuRefresh()
        if (!isReady) {
            pendingShowModules = true
            setConnectionState(false, "Connecting to Bruce; the modules menu will open when BLE is ready.")
            return
        }
        if (currentCommand != null || commandQueue.isNotEmpty()) {
            pendingShowModules = true
            setConnectionState(true, "Finishing the current Bruce command before opening modules.")
            return
        }
        val modules = mutableListOf(
            "Infrared",
            "Sub-GHz / RF",
            "NFC / RFID",
            "Bruce Wi-Fi scan (passive, BLE)",
            "Android phone Wi-Fi targets",
            "System & Wi-Fi"
        )
        modules += if (screenMirrorActive) "Stop screen mirror" else "Screen mirror (experimental)"
        modules += "Return to Bruce menu"
        AlertDialog.Builder(this)
            .setTitle("Bruce modules")
            .setItems(modules.toTypedArray()) { _, index ->
                when (modules[index]) {
                    "Infrared" -> showModuleActions(
                        "Infrared",
                        listOf(
                            "Receive decoded signal (10 s)" to {
                                runModuleCommand("IR receive", "ir rx")
                            },
                            "Receive raw signal (10 s)" to {
                                runModuleCommand("IR raw receive", "ir rx -raw")
                            },
                            "Transmit saved .ir file" to {
                                chooseModuleFile("/BruceIR", setOf("ir")) { path ->
                                    runModuleCommand(
                                        "Transmit ${path.substringAfterLast('/')}",
                                        "ir tx_from_file ${quoteCliArgument(path)} false",
                                        "Transmit this infrared file now?"
                                    )
                                }
                            }
                        )
                    )
                    "Sub-GHz / RF" -> showModuleActions(
                        "Sub-GHz / RF",
                        listOf(
                            "Receive on configured frequency" to {
                                runModuleCommand("RF receive", "rf rx")
                            },
                            "Receive raw signal" to {
                                runModuleCommand("RF raw receive", "rf rx raw")
                            },
                            "Transmit saved .sub file" to {
                                chooseModuleFile("/BruceRF", setOf("sub")) { path ->
                                    runModuleCommand(
                                        "Transmit ${path.substringAfterLast('/')}",
                                        "rf tx_from_file ${quoteCliArgument(path)} false",
                                        "Transmit this Sub-GHz recording now?"
                                    )
                                }
                            }
                        )
                    )
                    "NFC / RFID" -> showModuleActions(
                        "NFC / RFID",
                        listOf(
                            "Read tag (5 s)" to {
                                runModuleCommand("RFID read", "rfid read 5000")
                            },
                            "Reader/module info" to {
                                runModuleCommand("RFID info", "rfid info")
                            },
                            "Save last read dump" to {
                                runModuleCommand(
                                    "Save RFID dump",
                                    "rfid save rfid_dump bruce",
                                    "Save the last RFID read to Bruce storage?"
                                )
                            },
                            "Load saved RFID dump" to {
                                chooseModuleFile("/BruceRFID", setOf("rfid", "nfc")) { path ->
                                    runModuleCommand(
                                        "Load ${path.substringAfterLast('/')}",
                                        "rfid loadfile ${quoteCliArgument(path)}"
                                    )
                                }
                            }
                        )
                    )
                    "Bruce Wi-Fi scan (passive, BLE)" -> {
                        runModuleCommand("Bruce Wi-Fi scan", "wifi scan")
                    }
                    "Android phone Wi-Fi targets" -> requestPhoneWifiTargets()
                    "System & Wi-Fi" -> showModuleActions(
                        "System & Wi-Fi",
                        listOf(
                            "Device information" to { runModuleCommand("Device information", "info") },
                            "RAM / BLE radio memory" to { runModuleCommand("RAM diagnostics", "free") },
                            "Uptime" to { runModuleCommand("Uptime", "uptime") },
                            "Wi-Fi status (no radio change)" to {
                                runModuleCommand("Wi-Fi status", "wifi status")
                            },
                            "Disconnect Wi-Fi" to { runModuleCommand("Wi-Fi off", "wifi off") },
                            "Enable Wi-Fi" to {
                                runModuleCommand(
                                    "Wi-Fi on",
                                    "wifi on",
                                    "This enables Wi-Fi on Bruce and may occupy its radio. Continue?"
                                )
                            },
                            "I2C scan" to { runModuleCommand("I2C scan", "i2c") },
                            "Run Bruce JavaScript" to {
                                chooseModuleFile("/BruceJS", setOf("js", "bjs")) { path ->
                                    runModuleCommand(
                                        "Run ${path.substringAfterLast('/')}",
                                        "js run_from_file ${quoteCliArgument(path)}",
                                        "Run this script on Bruce?"
                                    )
                                }
                            }
                        )
                    )
                    "Screen mirror (experimental)" -> confirmStartScreenMirror()
                    "Stop screen mirror" -> stopScreenMirror()
                    "Return to Bruce menu" -> returnToBruceMenu()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun requestPhoneWifiTargets() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            waitingForWifiScanPermission = true
            wifiLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scanPhoneWifiTargets()
    }

    @SuppressLint("MissingPermission")
    private fun scanPhoneWifiTargets() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            showWifiTargetsMessage("This phone does not provide Wi-Fi scanning.")
            return
        }
        if (!wifiManager.isWifiEnabled) {
            showWifiTargetsMessage("Enable Wi-Fi on the phone, then select Wi-Fi Targets again.")
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            locationManager?.isLocationEnabled != true
        ) {
            showWifiTargetsMessage("Enable Location in Android settings to allow Wi-Fi scanning.")
            return
        }

        clearPhoneWifiScan()
        setConnectionState(isReady, "Scanning nearby Wi-Fi targets...")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val results = try {
                    wifiManager.scanResults
                } catch (_: SecurityException) {
                    clearPhoneWifiScan()
                    showWifiTargetsMessage("Android denied access to Wi-Fi scan results.")
                    return
                }
                clearPhoneWifiScan()
                if (results.isNotEmpty()) {
                    lastWifiScanResults = results
                    updateWifiTargetsUi(results, "Nearby Wi-Fi targets")
                } else if (lastWifiScanResults.isNotEmpty()) {
                    updateWifiTargetsUi(lastWifiScanResults, "Recent Wi-Fi targets (Android throttled scan)")
                } else {
                    showWifiTargetsMessage("Android did not return any Wi-Fi scan results.")
                }
            }
        }
        wifiScanReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val started = try {
            wifiManager.startScan()
        } catch (_: SecurityException) {
            clearPhoneWifiScan()
            showWifiTargetsMessage("Android denied permission to start a Wi-Fi scan.")
            return
        }
        if (!started) {
            val recentResults = try {
                wifiManager.scanResults
            } catch (_: SecurityException) {
                clearPhoneWifiScan()
                showWifiTargetsMessage("Android denied access to Wi-Fi scan results.")
                return
            }
            clearPhoneWifiScan()
            if (recentResults.isNotEmpty()) {
                lastWifiScanResults = recentResults
                updateWifiTargetsUi(recentResults, "Recent scan results")
            } else {
                showWifiTargetsMessage("Android could not start a fresh Wi-Fi scan.")
            }
            return
        }
        val timeout = Runnable {
            val results = try {
                wifiManager.scanResults
            } catch (_: SecurityException) {
                clearPhoneWifiScan()
                showWifiTargetsMessage("Android denied access to Wi-Fi scan results.")
                return@Runnable
            }
            clearPhoneWifiScan()
            if (results.isNotEmpty()) {
                lastWifiScanResults = results
                updateWifiTargetsUi(results, "Scan results (timed out)")
            }
        }
        wifiScanTimeout = timeout
        handler.postDelayed(timeout, WIFI_SCAN_TIMEOUT_MS)
    }

    private fun updateWifiTargetsUi(results: List<android.net.wifi.ScanResult>, title: String) {
        if (selectedWifiBssid != null && wifiDetailDialog?.isShowing == true) {
            val target = results.firstOrNull { it.BSSID == selectedWifiBssid }
            if (target != null) {
                showWifiTargetDetails(target)
            }
        } else {
            showPhoneWifiTargets(results, title)
        }
        scheduleWifiAutoRefresh()
    }

    @SuppressLint("MissingPermission")
    private fun showPhoneWifiTargets(results: List<android.net.wifi.ScanResult>, title: String) {
        val sortedResults = results.sortedByDescending { it.level }
        val rows = sortedResults.map { result ->
            val ssid = result.SSID
                .filter { it >= ' ' && it != '\u007f' }
                .takeIf { it.isNotBlank() } ?: "(hidden network)"
            val security = when {
                result.capabilities.contains("SAE") || result.capabilities.contains("WPA3") -> "WPA3"
                result.capabilities.contains("EAP") -> "Enterprise"
                result.capabilities.contains("WPA2") -> "WPA2"
                result.capabilities.contains("WPA") -> "WPA"
                result.capabilities.contains("WEP") -> "WEP"
                else -> "Open"
            }
            val percent = calculateWifiSignalPercent(result.level)
            val gauge = buildSignalGauge(percent)
            "$ssid\n$security  |  Ch ${wifiChannel(result.frequency)}  |  $gauge  (${result.level} dBm)"
        }.ifEmpty { listOf("No Wi-Fi networks found.") }

        val autoText = if (wifiAutoRefreshIntervalMs > 0) "${wifiAutoRefreshIntervalMs / 1000}s" else "OFF"

        wifiDetailDialog?.dismiss()
        wifiDetailDialog = null
        selectedWifiBssid = null

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(rows.toTypedArray()) { _, index ->
                if (index in sortedResults.indices) {
                    val target = sortedResults[index]
                    selectedWifiBssid = target.BSSID
                    showWifiTargetDetails(target)
                }
            }
            .setPositiveButton("REFRESH") { _, _ ->
                scanPhoneWifiTargets()
            }
            .setNeutralButton("AUTO: $autoText") { _, _ ->
                cycleWifiAutoRefreshInterval()
                scanPhoneWifiTargets()
            }
            .setNegativeButton("CLOSE") { _, _ ->
                wifiAutoRefreshIntervalMs = 0L
                wifiAutoRefreshRunnable?.let(handler::removeCallbacks)
            }

        wifiTargetDialog?.dismiss()
        val newDialog = dialogBuilder.create()
        wifiTargetDialog = newDialog
        newDialog.show()

        setConnectionState(isReady, "Wi-Fi targets active (Auto-refresh: $autoText)")
    }

    @SuppressLint("MissingPermission")
    private fun showWifiTargetDetails(target: android.net.wifi.ScanResult) {
        val ssid = target.SSID
            .filter { it >= ' ' && it != '\u007f' }
            .takeIf { it.isNotBlank() } ?: "(hidden network)"
        val percent = calculateWifiSignalPercent(target.level)
        val gauge = buildSignalGauge(percent)
        val quality = getSignalQualityText(percent)
        val freqGhz = String.format(java.util.Locale.US, "%.1f GHz", target.frequency / 1000.0)
        val autoText = if (wifiAutoRefreshIntervalMs > 0) "${wifiAutoRefreshIntervalMs / 1000}s" else "OFF"

        val detailsText = buildString {
            appendLine("SSID: $ssid")
            appendLine("BSSID (MAC): ${target.BSSID}")
            appendLine()
            appendLine("Signal Strength:  $gauge")
            appendLine("Level:  ${target.level} dBm  ($quality)")
            appendLine("Channel:  ${wifiChannel(target.frequency)}  ($freqGhz)")
            appendLine("Capabilities:  ${target.capabilities}")
            appendLine()
            appendLine("----------------------------------------------")
            appendLine("Auto-refresh interval:  $autoText")
            appendLine()
            appendLine("ℹ️ Note: Passive Wi-Fi beacon scanning reports AP signal %, BSSID, and channel.")
            appendLine("Counting connected client devices (STAs) requires raw 802.11 monitor mode frame sniffing (hardware/root).")
        }

        wifiTargetDialog?.dismiss()
        wifiTargetDialog = null

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("🎯 Target: $ssid")
            .setMessage(detailsText)
            .setPositiveButton("REFRESH NOW") { _, _ ->
                scanPhoneWifiTargets()
            }
            .setNeutralButton("INTERVAL: $autoText") { _, _ ->
                cycleWifiAutoRefreshInterval()
                scanPhoneWifiTargets()
            }
            .setNegativeButton("BACK") { _, _ ->
                selectedWifiBssid = null
                showPhoneWifiTargets(lastWifiScanResults, "Nearby Wi-Fi targets")
            }

        wifiDetailDialog?.dismiss()
        val newDialog = dialogBuilder.create()
        wifiDetailDialog = newDialog
        newDialog.show()

        setConnectionState(isReady, "Target $ssid: $percent% (${target.level} dBm)")
    }

    private fun cycleWifiAutoRefreshInterval() {
        wifiAutoRefreshIntervalMs = when (wifiAutoRefreshIntervalMs) {
            0L -> 2000L
            2000L -> 5000L
            5000L -> 10000L
            else -> 0L
        }
    }

    private fun scheduleWifiAutoRefresh() {
        wifiAutoRefreshRunnable?.let(handler::removeCallbacks)
        if (wifiAutoRefreshIntervalMs <= 0) return
        val runnable = Runnable {
            scanPhoneWifiTargets()
        }
        wifiAutoRefreshRunnable = runnable
        handler.postDelayed(runnable, wifiAutoRefreshIntervalMs)
    }

    private fun calculateWifiSignalPercent(rssiDbm: Int): Int = when {
        rssiDbm >= -50 -> 100
        rssiDbm <= -100 -> 0
        else -> 2 * (rssiDbm + 100)
    }.coerceIn(0, 100)

    private fun getSignalQualityText(percentage: Int): String = when {
        percentage >= 80 -> "Excellent 🟢"
        percentage >= 60 -> "Good 🟡"
        percentage >= 40 -> "Fair 🟧"
        percentage >= 20 -> "Weak 🔴"
        else -> "Very Poor ⚪"
    }

    private fun buildSignalGauge(percentage: Int): String {
        val filled = (percentage / 10).coerceIn(0, 10)
        val bar = "█".repeat(filled) + "░".repeat(10 - filled)
        return "$percentage% [$bar]"
    }

    private fun wifiChannel(frequencyMhz: Int): String = when (frequencyMhz) {
        in 2412..2472 -> ((frequencyMhz - 2407) / 5).toString()
        2484 -> "14"
        in 5035..5895 -> ((frequencyMhz - 5000) / 5).toString()
        in 5955..7115 -> ((frequencyMhz - 5950) / 5).toString()
        else -> "?"
    }

    private fun showWifiTargetsMessage(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi Targets")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
        setConnectionState(isReady, message)
    }

    private fun clearPhoneWifiScan() {
        wifiScanTimeout?.let(handler::removeCallbacks)
        wifiScanTimeout = null
        wifiScanReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        wifiScanReceiver = null
    }

    private fun showModuleActions(title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("BACK") { _, _ -> showModules() }
            .show()
    }

    private fun runModuleCommand(title: String, command: String, confirmation: String? = null) {
        if (confirmation != null) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(confirmation)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONTINUE") { _, _ -> runModuleCommandNow(title, command) }
                .show()
        } else {
            runModuleCommandNow(title, command)
        }
    }

    private fun runModuleCommandNow(title: String, command: String) {
        cancelInactiveMenuRefresh()
        if (!isReady) {
            setConnectionState(false, "Connect to Bruce before using modules.")
            return
        }
        if (currentCommand != null || commandQueue.isNotEmpty()) {
            setConnectionState(true, "Wait for the current Bruce operation to finish.")
            return
        }
        moduleCommandTitle = title
        moduleCommandText = command
        bruceMenu.text = "$title\n\nWaiting for Bruce..."
        setConnectionState(true, "Running $title...")
        enqueueCommand(command)
    }

    private fun showRfCaptureAnalysis(capture: String) {
        val cleaned = capture.trim()
        if (!cleaned.contains("Filetype: Bruce SubGhz File")) {
            bruceMenu.text = "RF capture\n\n${cleaned.ifBlank { "No signal was decoded." }}"
            setConnectionState(true, "RF capture finished; Bruce returned no .sub data.")
            return
        }

        pendingRfExport = if (cleaned.endsWith('\n')) cleaned else "$cleaned\n"
        val fields = cleaned.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }.toMap()
        val protocol = fields["Protocol"].orEmpty().ifBlank { "Unknown" }
        val frequency = fields["Frequency"].orEmpty().ifBlank { "Unknown" }
        val previousCapture = recentRfCaptures.lastOrNull {
            it["Protocol"] == protocol && it["Frequency"] == frequency
        }
        val comparedFields = listOf("Key", "Serial", "Counter", "Button", "Bit", "TE")
            .filter { field -> previousCapture?.containsKey(field) == true && fields.containsKey(field) }
        val changedFields = comparedFields.filter { fields[it] != previousCapture?.get(it) }
        recentRfCaptures.add(fields)
        while (recentRfCaptures.size > 12) recentRfCaptures.removeAt(0)
        val frequencyDisplay = frequency.toLongOrNull()?.let {
            "%.3f MHz".format(java.util.Locale.US, it / 1_000_000.0)
        } ?: frequency
        val summary = buildString {
            appendLine("Protocol: $protocol")
            appendLine("Frequency: $frequencyDisplay")
            fields["Bit"]?.let { appendLine("Bits: $it") }
            fields["Key"]?.let { appendLine("Key: $it") }
            fields["Manufacture"]?.let { appendLine("Manufacturer: $it") }
            fields["Serial"]?.let { appendLine("Serial: $it") }
            fields["Button"]?.let { appendLine("Button: $it") }
            fields["Counter"]?.let { appendLine("Counter: $it") }
            fields["TE"]?.let { appendLine("Timing: $it µs") }
            if (fields.containsKey("Serial") && fields.containsKey("Counter")) {
                appendLine("Rolling-code metadata: recognized by Bruce")
            }
            if (previousCapture != null) {
                appendLine()
                appendLine("Comparison with previous $protocol capture")
                if (comparedFields.isEmpty()) {
                    appendLine("No comparable key/counter fields were decoded.")
                } else if (changedFields.isEmpty()) {
                    appendLine("Decoded payload fields are unchanged: ${comparedFields.joinToString()}")
                } else {
                    appendLine("Changed fields: ${changedFields.joinToString()}")
                    (comparedFields - changedFields.toSet()).takeIf { it.isNotEmpty() }?.let {
                        appendLine("Unchanged: ${it.joinToString()}")
                    }
                }
                appendLine("Frame comparison alone cannot confirm rolling-code validation or pairing support.")
            } else {
                appendLine()
                appendLine("Capture again with the same button to compare decoded fields.")
            }
            appendLine()
            append("Edit the .sub text or save the original capture.")
        }

        AlertDialog.Builder(this)
            .setTitle("Sub-GHz signal analysis")
            .setMessage(summary)
            .setNeutralButton("EDIT .SUB") { _, _ ->
                val raw = EditText(this).apply {
                    setText(pendingRfExport)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setSingleLine(false)
                    minLines = 10
                    maxLines = 24
                    setPadding(24, 12, 24, 12)
                }
                val scroll = ScrollView(this).apply { addView(raw) }
                AlertDialog.Builder(this)
                    .setTitle("Edit Bruce .sub")
                    .setView(scroll)
                    .setPositiveButton("SEND TO BRUCE") { _, _ ->
                        pendingRfExport = raw.text.toString().trimEnd() + "\n"
                        uploadRfCaptureToBruce(pendingRfExport)
                    }
                    .setNeutralButton("SAVE TO PHONE") { _, _ ->
                        pendingRfExport = raw.text.toString().trimEnd() + "\n"
                        rfExportPicker.launch("bruce_rf_${System.currentTimeMillis()}.sub")
                    }
                    .setNegativeButton("CLOSE", null)
                    .show()
            }
            .setPositiveButton("SAVE .SUB") { _, _ ->
                rfExportPicker.launch("bruce_rf_${System.currentTimeMillis()}.sub")
            }
            .setNegativeButton("CLOSE", null)
            .show()

        LabSessionStore.recordEvent(
            this,
            "RF_CAPTURE",
            "$protocol at $frequency Hz; preserved as Bruce .sub"
        )
    }

    private fun uploadRfCaptureToBruce(content: String) {
        if (!content.startsWith("Filetype: Bruce SubGhz File")) {
            Toast.makeText(this, "This is not a Bruce .sub file.", Toast.LENGTH_LONG).show()
            return
        }
        setConnectionState(true, "Sending edited .sub file to Bruce over Wi-Fi…")
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                val rawUrl = prefs.getString("bruce_url", "http://bruce.local").orEmpty().trim()
                val baseUrl = if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) {
                    rawUrl.trimEnd('/')
                } else "http://${rawUrl.trimEnd('/')}"
                val username = prefs.getString("bruce_user", "admin").orEmpty()
                val password = prefs.getString("bruce_pass", "bruce").orEmpty()
                val auth = "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

                // Set the destination first for compatibility with firmware versions
                // that keep upload target state from the last /listfiles request.
                val folderUrl = URL("$baseUrl/listfiles?fs=LittleFS&folder=%2FBruceRF")
                (folderUrl.openConnection() as HttpURLConnection).also { listing ->
                    listing.connectTimeout = 8000
                    listing.readTimeout = 15000
                    listing.setRequestProperty("Authorization", auth)
                    val code = listing.responseCode
                    listing.inputStream?.close()
                    listing.disconnect()
                    if (code !in 200..299) error("Bruce file listing returned HTTP $code")
                }

                val filename = "bruceir_rf_${System.currentTimeMillis()}.sub"
                val boundary = "BruceIR-${System.currentTimeMillis()}"
                connection = (URL("$baseUrl/upload").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Authorization", auth)
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setChunkedStreamingMode(16 * 1024)
                }
                connection.outputStream.use { out ->
                    fun field(name: String, value: String) {
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(StandardCharsets.UTF_8))
                    }
                    field("folder", "/BruceRF")
                    field("fs", "LittleFS")
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                    out.write(content.toByteArray(StandardCharsets.UTF_8))
                    out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("Bruce upload returned HTTP $code${if (details.isBlank()) "" else ": $details"}")
                }
                handler.post {
                    setConnectionState(true, "Saved $filename in Bruce:/BruceRF")
                    Toast.makeText(this, "Edited .sub sent to Bruce:/BruceRF", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                handler.post {
                    setConnectionState(false, "Could not send .sub file to Bruce.")
                    Toast.makeText(this, "Upload failed: ${error.message ?: "connection error"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun chooseModuleFile(
        folder: String,
        extensions: Set<String>,
        onSelected: (String) -> Unit
    ) {
        if (!isReady) {
            setConnectionState(false, "Connect to Bruce before browsing files.")
            return
        }
        if (currentCommand != null || commandQueue.isNotEmpty()) {
            setConnectionState(true, "Wait for the current Bruce operation to finish.")
            return
        }
        requestModuleFileListing(folder, extensions, onSelected)
    }

    private fun requestModuleFileListing(
        folder: String,
        extensions: Set<String>,
        onSelected: (String) -> Unit
    ) {
        fileListingCallback = { response ->
            if (response.contains("error", ignoreCase = true) ||
                response.contains("does not exist", ignoreCase = true)
            ) {
                AlertDialog.Builder(this)
                    .setTitle("Could not load files")
                    .setMessage(response)
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val entries = response.lineSequence().mapNotNull { line ->
                    val fields = line.trim().split('\t')
                    if (fields.size < 2 || fields[0].isBlank()) null
                    else FileEntry(fields[0], fields[1] == "<DIR>", fields[1])
                }.toList()
                showModuleFileListing(folder, entries, extensions, onSelected)
            }
        }
        setConnectionState(true, "Loading files from ${folder}...")
        enqueueCommand("ls ${quoteCliArgument(folder)}")
    }

    private fun showModuleFileListing(
        folder: String,
        entries: List<FileEntry>,
        extensions: Set<String>,
        onSelected: (String) -> Unit
    ) {
        val shownEntries = buildList {
            if (folder != "/") add(FileEntry("..", true, "<DIR>"))
            addAll(entries.filter { entry ->
                entry.isDirectory || entry.name.substringAfterLast('.', "").lowercase() in extensions
            })
        }
        if (shownEntries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No matching files")
                .setMessage("No ${extensions.joinToString().uppercase()} files were found in $folder.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val list = ListView(this).apply {
            adapter = ArrayAdapter(
                this@RemoteConsoleActivity,
                android.R.layout.simple_list_item_1,
                shownEntries.map { entry ->
                    if (entry.isDirectory) "[DIR]  ${entry.name}"
                    else "${entry.name}  (${entry.size} B)"
                }
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Select file: $folder")
            .setView(list)
            .setNegativeButton("CANCEL", null)
            .create()
        list.setOnItemClickListener { _, _, index, _ ->
            val selected = shownEntries[index]
            dialog.dismiss()
            when {
                selected.name == ".." -> requestModuleFileListing(
                    folder.substringBeforeLast('/').ifEmpty { "/" },
                    extensions,
                    onSelected
                )
                selected.isDirectory -> requestModuleFileListing(
                    joinDevicePath(folder, selected.name),
                    extensions,
                    onSelected
                )
                else -> onSelected(joinDevicePath(folder, selected.name))
            }
        }
        dialog.show()
    }

    private fun confirmStartScreenMirror() {
        if (!isReady) {
            setConnectionState(false, "Connect to Bruce before starting the screen mirror.")
            return
        }
        if (currentCommand != null || commandQueue.isNotEmpty()) {
            setConnectionState(true, "Wait for the current Bruce operation to finish.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Experimental screen mirror")
            .setMessage(
                "This starts Bruce's display stream over BLE. It can be slow or unstable, " +
                    "and previously caused LilyGO resets. Use only for a test; stop it from Modules."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("START TEST") { _, _ ->
                waitingForSelectionRefresh = false
                mirrorWireBuffer.reset()
                screenMirrorActive = true
                screenMirrorView.updateScreen(byteArrayOf())
                setScreenMirrorVisible(true)
                runModuleCommandNow("Start screen mirror", "display start")
            }
            .show()
    }

    private fun stopScreenMirror() {
        if (!screenMirrorActive) return
        if (!isReady) {
            setConnectionState(false, "BLE is disconnected; the screen stream may still be running.")
            return
        }
        moduleCommandTitle = "Stop screen mirror"
        moduleCommandText = "display stop"
        setConnectionState(true, "Stopping screen mirror...")
        if (currentCommand == null && commandQueue.isEmpty()) {
            enqueueCommand("display stop")
        } else {
            commandQueue.addLast("display stop")
        }
    }

    private fun closeRemoteConsole() {
        if (screenMirrorActive) {
            if (!isReady) {
                AlertDialog.Builder(this)
                    .setTitle("Screen mirror may still be running")
                    .setMessage(
                        "Bluetooth is disconnected, so Bruce cannot receive the stop command. " +
                            "The LilyGO screen stream may continue until Bruce is restarted."
                    )
                    .setNegativeButton("STAY", null)
                    .setPositiveButton("LEAVE") { _, _ -> finish() }
                    .show()
                return
            }
            closeAfterMirrorStop = true
            stopScreenMirror()
        } else {
            finish()
        }
    }

    @Deprecated("Use the back dispatcher")
    override fun onBackPressed() {
        closeRemoteConsole()
    }

    private fun setScreenMirrorVisible(visible: Boolean) {
        menuScroll.visibility = if (visible) View.GONE else View.VISIBLE
        screenMirrorView.visibility = if (visible) View.VISIBLE else View.GONE
        titleView.text = if (visible) "BRUCE SCREEN MIRROR" else "BRUCE MENU"
    }

    private fun returnToBruceMenu() {
        cancelInactiveMenuRefresh()
        if (!isReady) {
            setConnectionState(false, "Connect to Bruce before loading its menu.")
            return
        }
        if (screenMirrorActive) {
            stopScreenMirror()
            return
        }
        if (currentCommand != null || commandQueue.isNotEmpty()) {
            setConnectionState(true, "Wait for the current Bruce operation to finish.")
            return
        }
        setScreenMirrorVisible(false)
        bruceMenu.text = lastMenuText.ifBlank { "Loading Bruce menu..." }
        enqueueCommand(MENU_COMMAND)
    }

    private var fileListingCallback: ((String) -> Unit)? = null
    private var fileReadCallback: ((String) -> Unit)? = null
    private var fileOperationCallback: ((String) -> Unit)? = null

    private fun requestFileListing(path: String) {
        cancelInactiveMenuRefresh()
        if (!isReady) {
            setConnectionState(false, "Connect to Bruce before browsing files.")
            return
        }
        fileListingCallback = { response -> showFileListing(path, response) }
        enqueueCommand("ls ${quoteCliArgument(path)}")
    }

    private fun showFileListing(path: String, response: String) {
        val entries = response.lineSequence().mapNotNull { line ->
            val fields = line.trim().split('\t')
            if (fields.size < 2 || fields[0].isBlank()) null
            else FileEntry(fields[0], fields[1] == "<DIR>", fields[1])
        }.toList()
        val displayEntries = buildList {
            if (path != "/") add(FileEntry("..", true, "<DIR>"))
            addAll(entries)
        }
        val listView = ListView(this).apply {
            adapter = ArrayAdapter(
                this@RemoteConsoleActivity,
                android.R.layout.simple_list_item_1,
                displayEntries.map { entry ->
                    if (entry.isDirectory) "[DIR]  ${entry.name}" else "${entry.name}  (${entry.size} B)"
                }
            )
        }
        val fileBrowser = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        fileBrowser.addView(android.widget.Button(this).apply {
            text = "UPLOAD SMALL TEXT FILE OVER BLE"
            setOnClickListener {
                pendingBleUploadFolder = path
                bleUploadPicker.launch(arrayOf("*/*"))
            }
        }, android.widget.LinearLayout.LayoutParams(-1, -2))
        fileBrowser.addView(android.widget.TextView(this).apply {
            text = "For binary or large files, use the full Wi-Fi file manager."
            setTextColor(getColor(R.color.white))
            setPadding(12, 8, 12, 8)
        })
        fileBrowser.addView(android.widget.Button(this).apply {
            text = "OPEN FULL FILE MANAGER (WI-FI)"
            setOnClickListener {
                startActivity(Intent(this@RemoteConsoleActivity, BruceFileManagerActivity::class.java))
            }
        }, android.widget.LinearLayout.LayoutParams(-1, -2))
        fileBrowser.addView(listView, android.widget.LinearLayout.LayoutParams(
            -1,
            (360 * resources.displayMetrics.density).toInt()
        ))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bruce files: $path")
            .setView(fileBrowser)
            .setPositiveButton("NEW FOLDER", null)
            .setNeutralButton(if (path == "/") "REFRESH" else "UP", null)
            .setNegativeButton("CLOSE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = EditText(this).apply { hint = "Folder name" }
                AlertDialog.Builder(this)
                    .setTitle("Create folder")
                    .setView(input)
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CREATE") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty() && !name.contains('/') && !name.contains('\\')) {
                            val childPath = joinDevicePath(path, name)
                            runFileOperation("mkdir", childPath) { requestFileListing(path) }
                        }
                    }
                    .show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                requestFileListing(if (path == "/") "/" else path.substringBeforeLast('/').ifEmpty { "/" })
                dialog.dismiss()
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = displayEntries[position]
            if (selected.name == "..") {
                dialog.dismiss()
                requestFileListing(path.substringBeforeLast('/').ifEmpty { "/" })
            } else if (selected.isDirectory) {
                dialog.dismiss()
                requestFileListing(joinDevicePath(path, selected.name))
            } else {
                dialog.dismiss()
                val fileSize = selected.size.toLongOrNull()
                if (fileSize != null && fileSize > MAX_BLE_TEXT_DOWNLOAD_BYTES) {
                    AlertDialog.Builder(this)
                        .setTitle("File is too large for BLE preview")
                        .setMessage("This file is ${fileSize} bytes. BLE preview is limited to about $MAX_BLE_TEXT_DOWNLOAD_BYTES bytes. Use Wi-Fi for full-size and binary transfers.")
                        .setPositiveButton("OPEN WI-FI FILE MANAGER") { _, _ ->
                            startActivity(Intent(this, BruceFileManagerActivity::class.java))
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                } else if (selected.name.substringAfterLast('.', "").lowercase() !in TEXT_FILE_EXTENSIONS) {
                    AlertDialog.Builder(this)
                        .setTitle("Binary file")
                        .setMessage("Binary files cannot be previewed over the text-based BLE console. Use the Wi-Fi file manager to upload or download this file.")
                        .setPositiveButton("OPEN WI-FI FILE MANAGER") { _, _ ->
                            startActivity(Intent(this, BruceFileManagerActivity::class.java))
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                } else {
                    showTextFile(joinDevicePath(path, selected.name))
                }
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selected = displayEntries[position]
            if (selected.name == "..") return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("Delete ${selected.name}?")
                .setMessage("This cannot be undone.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE") { _, _ ->
                    runFileOperation(if (selected.isDirectory) "rmdir" else "rm", joinDevicePath(path, selected.name)) {
                        requestFileListing(path)
                    }
                }
                .show()
            true
        }
        dialog.show()
    }

    private fun showTextFile(path: String) {
        if (path.substringAfterLast('.', "").lowercase() !in TEXT_FILE_EXTENSIONS) {
            AlertDialog.Builder(this)
                .setMessage("This file type cannot be previewed through the text-based BLE console.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        fileReadCallback = { content ->
            val text = TextView(this).apply {
                setPadding(24, 16, 24, 16)
                textSize = 13f
                gravity = Gravity.START
                setTextIsSelectable(true)
                text = content.ifEmpty { "(empty file)" }
            }
            val scroll = ScrollView(this).apply { addView(text) }
            AlertDialog.Builder(this)
                .setTitle(path.substringAfterLast('/'))
                .setView(scroll)
                .setPositiveButton("SAVE TO PHONE") { _, _ ->
                    pendingBleDownload = content
                    bleDownloadPicker.launch(path.substringAfterLast('/').ifBlank { "bruce_file.txt" })
                }
                .setNegativeButton("CLOSE", null)
                .show()
        }
        enqueueCommand("cat ${quoteCliArgument(path)}")
    }

    private fun prepareBleTextUpload(uri: android.net.Uri, folder: String) {
        if (!isReady || currentCommand != null || commandQueue.isNotEmpty()) {
            setConnectionState(isReady, "Wait for the current Bruce operation to finish before uploading.")
            return
        }
        val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)
            ?: "upload.txt"
        if (fileName.contains('"') || fileName.contains('\n') || fileName.contains('\r')) {
            Toast.makeText(this, "The selected file has an unsupported name.", Toast.LENGTH_LONG).show()
            return
        }
        val targetPath = joinDevicePath(folder, fileName)
        setConnectionState(true, "Reading ${fileName} for BLE transfer…")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(2048)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_BLE_TEXT_UPLOAD_BYTES) {
                            error("BLE text transfers are limited to $MAX_BLE_TEXT_UPLOAD_BYTES bytes.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("The selected file could not be opened")
                if (bytes.isEmpty()) error("Empty files are not supported over BLE.")
                val text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                if ('\u0000' in text) error("Binary files cannot be sent through the text-based BLE transfer.")
                if (text.lineSequence().any { it.trimEnd('\r') == "EOF" }) {
                    error("The file contains the reserved BLE end marker on its own line.")
                }
                val filePayload = if (bytes.last() == '\n'.code.toByte()) bytes else bytes + byteArrayOf('\n'.code.toByte())
                if (filePayload.size > MAX_BLE_TEXT_UPLOAD_BYTES) {
                    error("The file is larger than the $MAX_BLE_TEXT_UPLOAD_BYTES-byte BLE transfer limit.")
                }
                // Bruce's existing storage write command reads newline-delimited
                // text and terminates on a line containing exactly EOF.
                val wirePayload = filePayload + "EOF\n".toByteArray(StandardCharsets.UTF_8)
                handler.post {
                    if (!isReady || currentCommand != null || commandQueue.isNotEmpty()) {
                        setConnectionState(isReady, "Bruce became busy; retry the file upload.")
                        return@post
                    }
                    pendingBleUploadPath = targetPath
                    pendingBleUploadBytes = wirePayload
                    bleUploadAwaitingInput = true
                    bleUploadSending = false
                    setConnectionState(true, "Starting BLE upload to $targetPath…")
                    enqueueCommand("storage write ${quoteCliArgument(targetPath)} ${filePayload.size}")
                    scheduleBleUploadTimeout()
                }
            } catch (error: Exception) {
                handler.post {
                    setConnectionState(false, "Could not prepare BLE file upload.")
                    Toast.makeText(this, "Upload failed: ${error.message ?: "invalid text file"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun queueBleUploadPayload() {
        if (!bleUploadAwaitingInput) return
        val payload = pendingBleUploadBytes ?: run {
            failCurrentCommand("BLE upload data is unavailable.")
            return
        }
        bleUploadAwaitingInput = false
        bleUploadSending = true
        clearBleUploadTimeout()
        scheduleBleUploadTimeout()
        var offset = 0
        while (offset < payload.size) {
            val end = (offset + maxWriteBytes).coerceAtMost(payload.size)
            pendingWrites.addLast(payload.copyOfRange(offset, end))
            offset = end
        }
        setConnectionState(true, "Sending ${payload.size} text bytes over BLE…")
        writeNextChunk()
    }

    private fun scheduleBleUploadTimeout() {
        clearBleUploadTimeout()
        val timeout = Runnable {
            if (pendingBleUploadPath != null) {
                pendingWrites.clear()
                pendingBleUploadBytes = null
                pendingBleUploadPath = null
                bleUploadAwaitingInput = false
                bleUploadSending = false
                failCurrentCommand("BLE file upload timed out; check Bruce storage before retrying.")
            }
        }
        bleUploadTimeoutRunnable = timeout
        handler.postDelayed(timeout, BLE_UPLOAD_TIMEOUT_MS)
    }

    private fun clearBleUploadTimeout() {
        bleUploadTimeoutRunnable?.let(handler::removeCallbacks)
        bleUploadTimeoutRunnable = null
    }

    private fun runFileOperation(operation: String, path: String, onComplete: () -> Unit) {
        fileOperationCallback = { response ->
            if (response.contains("Error", ignoreCase = true) ||
                response.contains("does not exist", ignoreCase = true)
            ) {
                AlertDialog.Builder(this)
                    .setMessage(response)
                    .setPositiveButton("OK", null)
                    .show()
            }
            onComplete()
        }
        enqueueCommand("$operation ${quoteCliArgument(path)}")
    }

    private fun quoteCliArgument(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")}\""

    private fun joinDevicePath(parent: String, child: String): String =
        if (parent == "/") "/$child" else "$parent/$child"

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        isReady = false
        val currentGatt = gatt
        gatt = null
        serialCharacteristic = null
        currentGatt?.disconnect()
        currentGatt?.close()
    }

    private fun clearConnection(disconnectedGatt: BluetoothGatt) {
        if (gatt === disconnectedGatt) gatt = null
        serialCharacteristic = null
        isReady = false
        writeInFlight = false
        currentCommand = null
        menuResponseWatchdog?.let(handler::removeCallbacks)
        menuResponseWatchdog = null
        commandQueue.clear()
        pendingWrites.clear()
        clearBleUploadTimeout()
        pendingBleUploadPath = null
        pendingBleUploadBytes = null
        bleUploadAwaitingInput = false
        bleUploadSending = false
    }

    override fun onDestroy() {
        stopScan()
        wifiAutoRefreshIntervalMs = 0L
        wifiAutoRefreshRunnable?.let(handler::removeCallbacks)
        wifiTargetDialog?.dismiss()
        wifiDetailDialog?.dismiss()
        clearPhoneWifiScan()
        handler.removeCallbacksAndMessages(null)
        inactiveMenuRefreshRunnable = null
        waitingForSelectionRefresh = false
        invalidMenuRetryRunnable = null
        commandStartRunnable = null
        disconnect()
        super.onDestroy()
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91")
        private val CHARACTERISTIC_UUID = UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val MAX_COMMAND_BYTES = 512
        private const val MAX_TEXT_BUFFER = 256
        private const val MAX_COMMAND_OUTPUT = 256 * 1024
        private const val MAX_MENU_ERROR_PREVIEW = 240
        private const val MAX_BLE_TEXT_UPLOAD_BYTES = 4096
        private const val MAX_BLE_TEXT_DOWNLOAD_BYTES = 4096L
        private const val BLE_UPLOAD_READY_MARKER = "Reading input data from serial buffer until EOF"
        private const val BLE_UPLOAD_TIMEOUT_MS = 45_000L
        private const val MAX_MENU_REFRESH_ATTEMPTS = 12
        private const val MENU_REFRESH_INTERVAL_MS = 600L
        private const val INACTIVE_MENU_REFRESH_INTERVAL_MS = 1_000L
        private const val MENU_RESPONSE_START_TIMEOUT_MS = 12_000L
        private const val MENU_RESPONSE_IDLE_TIMEOUT_MS = 4_000L
        private const val MENU_INITIAL_REFRESH_DELAY_MS = 350L
        private const val MAX_INVALID_MENU_RETRIES = 2
        private const val INVALID_MENU_RETRY_DELAY_MS = 800L
        private const val NAVIGATION_COMMAND_INTERVAL_MS = 160L
        private const val WIFI_SCAN_TIMEOUT_MS = 15_000L
        private const val MENU_COMMAND = "optionsJSON"
        private const val DESIRED_BLE_MTU = 247
        private const val DISPLAY_PACKET_HEADER = 0xAA
        private val WIFI_NETWORK_LABEL = Regex("""^(#?)(.*)\((-?\d+)\|([^|]+)\|ch\.(\d+)\)$""")
        private val TEXT_FILE_EXTENSIONS =
            setOf("txt", "ir", "sub", "nfc", "rfid", "json", "js", "py", "csv", "log", "html", "conf")
    }

    private data class FileEntry(val name: String, val isDirectory: Boolean, val size: String)
}
