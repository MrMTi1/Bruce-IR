package com.example.bruceir

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Html
import android.util.Log
import android.util.TypedValue
import java.io.File
import java.net.UnknownHostException
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.transition.TransitionManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bruceir.databinding.ActivityMainBinding
import com.example.bruceir.ui.SignalView
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val MAX_SMART_MACRO_RESULTS = 100
private const val STATE_BLE_CAPTURE_TARGET_PATH = "ble_capture_target_path"
private const val WIFI_IR_CAPTURE_TIMEOUT_MS = 70_000

private enum class IrCaptureTransport {
    BLE,
    WIFI
}

private class IrCaptureNoSignalException : IOException()

class MainActivity : AppCompatActivity() {

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private lateinit var binding: ActivityMainBinding
    private lateinit var transmitter: IrTransmitter
    private lateinit var adapter: CommandAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var allData = IrFolder("Moje Piloty")
    private var currentFolder = allData
    private var directIrLibraryMode = false
    private var directIrLibraryRoot: IrFolder? = null
    private var directIrPilotFolder: IrFolder? = null
    private var directIrSettingsFolder: IrFolder? = null
    private var recentFolder = IrFolder("RECENTLY USED")
    private var downloadedFolder = IrFolder("DOWNLOADED")
    private var pendingFlipperDbImport = false
    private val folderNavigationStack = java.util.ArrayDeque<IrFolder>()
    private var isEditMode = false
    private lateinit var macroManager: MacroManager
    private val gson = Gson()
    private var bridgeJob: Job? = null
    private var bleCaptureTargetFolderPath: List<String>? = null
    private var wifiCaptureJob: Job? = null
    private var wifiCaptureDialog: AlertDialog? = null
    private var pendingRemotePath: List<String>? = null
    private var pendingWorkbenchAction: String? = null
    private var hasLoadedIrData = false
    private var lastBruceConnectionMessage: String? = null
    private var lastBruceConnectionSuccessful: Boolean? = null

    private var wifiScanReceiver: BroadcastReceiver? = null
    private var wifiScanTimeout: Runnable? = null
    private var wifiAutoRefreshRunnable: Runnable? = null
    private var wifiAutoRefreshIntervalMs: Long = 0L
    private var selectedWifiBssid: String? = null
    private var lastWifiScanResults: List<ScanResult> = emptyList()
    private var sortedWifiResults: List<ScanResult> = emptyList()
    private var wifiTargetDialog: AlertDialog? = null
    private var wifiDetailDialog: AlertDialog? = null
    private var wifiTargetAdapter: WifiTargetAdapter? = null
    private var wifiTargetListView: ListView? = null
    private var wifiColorEnabled: Boolean = true
    private var waitingForWifiScanPermission = false

    private val wifiLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && waitingForWifiScanPermission) {
            waitingForWifiScanPermission = false
            scanPhoneWifiTargets()
        } else {
            waitingForWifiScanPermission = false
            Toast.makeText(this, "Location permission is required for Wi-Fi scanning.", Toast.LENGTH_SHORT).show()
        }
    }
    private val bleTransmitterPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (bleTransmitterPermissions().all {
                grants[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            activateBleTransmitter()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to use LilyGo as an IR transmitter.", Toast.LENGTH_LONG).show()
        }
    }
    private val enableBleTransmitterBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) BruceBleIrLink.connect(this)
        else Toast.makeText(this, "Bluetooth must be enabled to connect to LilyGo.", Toast.LENGTH_LONG).show()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importBruceFile(it) } }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { performExport(it) } }
    private val importFullDbLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { performFullImport(it) } }
    private val hcFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importAndSelectHcFile(it) } }
    private val wordlistFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importAndSelectWordlistFile(it) } }
    private val bleConsoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targetFolderPath = bleCaptureTargetFolderPath
        bleCaptureTargetFolderPath = null
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        if (targetFolderPath == null) {
            Toast.makeText(this, "Nie można ustalić folderu docelowego przechwycenia.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val capture = result.data?.getStringExtra(BruceBleConsoleActivity.EXTRA_IR_CAPTURE) ?: return@registerForActivityResult
        val parsedCapture = parseBruceIrCapture(capture)
        if (parsedCapture != null) {
            val targetFolder = resolveFolderPath(targetFolderPath)
            if (targetFolder == null) {
                Toast.makeText(this, "The selected remote folder could not be found.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            navigateToRemoteFolder(targetFolder)
            showLiveCaptureDialog(
                parsedCapture,
                targetFolder,
                onRecapture = { startBleCapture(targetFolder) }
            )
        } else {
            Toast.makeText(this, "Could not parse the captured IR signal", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, insets.top, view.paddingRight, view.paddingBottom)
            windowInsets
        }
        bleCaptureTargetFolderPath = savedInstanceState
            ?.getStringArrayList(STATE_BLE_CAPTURE_TARGET_PATH)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("dpad_auto_default_migrated", false)) {
            prefs.edit()
                .putBoolean("global_dpad_organize", true)
                .putBoolean("dpad_auto_default_migrated", true)
                .apply()
        }
        isEditMode = prefs.getBoolean("edit_lock_mode", false)

        transmitter = IrTransmitter(this)
        macroManager = MacroManager(this, transmitter)
        BruceBleIrLink.setListener { status ->
            runOnUiThread {
                if (!isFinishing && transmitter.currentMode == IrTransmitter.Mode.BRUCE_BLE) {
                    binding.tvHomeIrStatus.text = "LilyGo Bluetooth: $status"
                }
            }
        }

        setupRemotes()
        setupTouchHelper()
        setupMainToolBar()
        setupHeaderActions()
        startBridgeServer()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {}
        }

        // Start at the app's root tools/library view. The pilot library is one
        // section here, rather than the screen that hides the rest of BruceIR.
        directIrLibraryMode = false
        lifecycleScope.launch {
            val loadedData = withContext(Dispatchers.IO) {
                BruceUtils.loadAllDataSuspend(this@MainActivity)
            }
            if (!hasLoadedIrData) {
                allData = loadedData
                ensureSystemHubFolders()
                hasLoadedIrData = true
                directIrLibraryRoot = allData.items.firstOrNull {
                    it is IrFolder && it.name.contains("PILOTY", ignoreCase = true)
                } as? IrFolder
                currentFolder = allData
                refreshList()
                runPendingWorkbenchActionIfReady()
            }
        }

        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (folderNavigationStack.isNotEmpty()) {
                    currentFolder = folderNavigationStack.pop()
                    refreshList()
                } else if (directIrLibraryMode && currentFolder == directIrLibraryRoot) {
                    finish()
                } else if (directIrLibraryMode && folderNavigationStack.isEmpty()) {
                    finish()
                } else if (currentFolder != allData) {
                    currentFolder = allData
                    refreshList()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        bleCaptureTargetFolderPath?.let {
            outState.putStringArrayList(STATE_BLE_CAPTURE_TARGET_PATH, ArrayList(it))
        }
        super.onSaveInstanceState(outState)
    }

    private fun setupTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (!isEditMode) return false
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter.getTouchAdapter().onItemMove(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            
            override fun isLongPressDragEnabled(): Boolean = isEditMode

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (isEditMode) {
                    // Update our data model with the new order from the adapter
                    currentFolder.items.clear()
                    currentFolder.items.addAll(adapter.currentList)
                    save()
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun showLiveCaptureDialog(
        json: String,
        targetFolder: IrFolder = currentFolder,
        onFinished: (() -> Unit)? = null,
        onRecapture: (() -> Unit)? = null,
        onSaved: (() -> Unit)? = null
    ) {
        try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val ir = obj.getAsJsonObject("ir") ?: obj.getAsJsonObject("rf") ?: return
            val pattern = gson.fromJson(ir.getAsJsonArray("pattern"), IntArray::class.java)
            val frequency = ir.get("frequency")?.asInt ?: 38000
            val totalDurationUs = pattern.sumOf { it.toLong() }

            val lay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 40)
            }

            val durationText = if (totalDurationUs >= 1_000_000) {
                String.format("%.2f s", totalDurationUs / 1_000_000.0)
            } else {
                String.format("%.1f ms", totalDurationUs / 1_000.0)
            }
            lay.addView(TextView(this).apply {
                text = "RAW • $frequency Hz • ${pattern.size} pulses • $durationText"
                setPadding(0, 0, 0, 20)
            })

            val signalView = SignalView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300)
                setPattern(pattern)
            }
            lay.addView(TextView(this).apply { text = "Signal waveform:"; setPadding(0, 0, 0, 20) })
            lay.addView(signalView)

            var defaultButtonNumber = 1
            while (targetFolder.items.any { it is Command && it.name == "Btn$defaultButtonNumber" }) {
                defaultButtonNumber++
            }
            val nameIn = EditText(this).apply {
                hint = "Name this signal"
                setText("Btn$defaultButtonNumber")
                selectAll()
            }
            lay.addView(TextView(this).apply { text = "Button name:"; setPadding(0, 40, 0, 10) })
            lay.addView(nameIn)

            val iconRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 20)
            }
            var selectedEmoji = "📡"
            val iconDisplay = TextView(this).apply {
                text = selectedEmoji
                textSize = 32f
                setPadding(20, 20, 40, 20)
                setBackgroundResource(R.drawable.button_glass_bg)
                setOnClickListener { showEmojiPicker { emoji -> text = emoji; selectedEmoji = emoji } }
            }
            iconRow.addView(iconDisplay)
            iconRow.addView(TextView(this).apply { text = "Tap to choose emoji" })
            lay.addView(iconRow)

            AlertDialog.Builder(this)
                .setTitle("SIGNAL CAPTURED")
                .setView(lay)
                .setPositiveButton("SAVE") { _, _ ->
                    val name = nameIn.text.toString().trim().ifEmpty { "Btn$defaultButtonNumber" }
                    val newCmd = Command(name, frequency, pattern, selectedEmoji)
                    targetFolder.items.add(newCmd)
                    targetFolder.isDpadOrganized = false
                    val autoOrganize = getSharedPreferences("settings", MODE_PRIVATE)
                        .getBoolean("global_dpad_organize", true)
                    if (autoOrganize) {
                        targetFolder.isDpadOrganized = true
                        if (!organizeFolderLayout(
                                targetFolder,
                                showToast = false,
                                onDone = {
                                    onFinished?.invoke()
                                }
                            )
                        ) {
                            save {
                                runOnUiThread {
                                    refreshList()
                                    onFinished?.invoke()
                                }
                            }
                        }
                    } else {
                        save {
                            runOnUiThread {
                                refreshList()
                                onFinished?.invoke()
                            }
                        }
                    }
                    onSaved?.invoke()
                }
                .setNegativeButton("DISCARD") { _, _ -> onFinished?.invoke() }
                .apply {
                    if (onRecapture != null) {
                        setNeutralButton("CAPTURE AGAIN") { _, _ -> onRecapture() }
                    }
                }
                .show()

        } catch (e: Exception) {
            android.util.Log.e("BruceIR", "Capture Error: ${e.message}")
        }
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra("ACTION") ?: return
        // Profile gate: intenty z innych ekranów nie mogą ominąć aktywnego profilu.
        val targetGroup = when (action) {
            "OPEN_MACROS", "OPEN_SMART_MACRO" -> AppProfileManager.ModuleGroup.MACROS
            "OPEN_PCAP_CONVERTER" -> AppProfileManager.ModuleGroup.NETWORK
            else -> AppProfileManager.ModuleGroup.IR
        }
        if (!AppProfileManager.isModuleAvailable(this, targetGroup)) {
            Toast.makeText(
                this,
                "Moduł niedostępny w profilu: ${AppProfileManager.profileLabel(AppProfileManager.getProfileMode(this))}.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when (action) {
            "OPEN_MACROS" -> startActivity(Intent(this, MacroWorkbenchActivity::class.java))
            "OPEN_PCAP_CONVERTER" -> startActivity(Intent(this, PcapConverterActivity::class.java))
            "OPEN_SMART_MACRO", "RUN_TV_B_GONE_BLAST", "ADD_TV_B_GONE_REMOTE",
            "OPEN_IR_LIBRARY", "IR_ADD_FOLDER", "IR_ADD_MANUAL", "IR_ORGANIZE_DPAD",
            "IR_LAYOUT_SETTINGS", "IR_IMPORT_FILE", "IR_IMPORT_URL",
            "LIVE_CAPTURE_BLE", "LIVE_CAPTURE_WIFI", "EXPORT_IR_BACKUP", "IMPORT_IR_BACKUP" -> {
                pendingWorkbenchAction = action
                runPendingWorkbenchActionIfReady()
            }
            "OPEN_IR_REMOTE_PATH" -> {
                pendingRemotePath = intent.getStringArrayListExtra("IR_REMOTE_PATH")
                if (hasLoadedIrData) openPendingRemoteFolder()
            }
        }
    }

    /** Sprawdza dostęp do grupy modułów w aktywnym profilu i pokazuje komunikat przy odmowie. */
    private fun moduleAllowed(group: AppProfileManager.ModuleGroup): Boolean {
        if (group == AppProfileManager.ModuleGroup.CORE) return true
        if (AppProfileManager.isModuleAvailable(this, group)) return true
        Toast.makeText(
            this,
            "Moduł niedostępny w profilu: ${AppProfileManager.profileLabel(AppProfileManager.getProfileMode(this))}.",
            Toast.LENGTH_SHORT
        ).show()
        return false
    }

    private fun runPendingWorkbenchActionIfReady() {
        if (!hasLoadedIrData) return
        val action = pendingWorkbenchAction ?: return
        pendingWorkbenchAction = null
        if (action in setOf(
                "OPEN_IR_LIBRARY",
                "IR_ADD_FOLDER",
                "IR_ADD_MANUAL",
                "IR_ORGANIZE_DPAD",
                "IR_LAYOUT_SETTINGS",
                "IR_IMPORT_FILE",
                "IR_IMPORT_URL"
            )
        ) {
            directIrLibraryMode = true
        }
        when (action) {
            "OPEN_SMART_MACRO" -> showSmartMacroSearchDialog()
            "RUN_TV_B_GONE_BLAST" -> executeTvBGoneBlast()
            "ADD_TV_B_GONE_REMOTE" -> addTvBGoneRemote()
            "OPEN_IR_LIBRARY" -> {
                val library = directIrLibraryRoot
                if (library == null) {
                    Toast.makeText(this, "IR library folder was not found.", Toast.LENGTH_SHORT).show()
                } else {
                    directIrLibraryMode = true
                    folderNavigationStack.clear()
                    currentFolder = library
                    refreshList()
                }
            }
            "IR_ADD_FOLDER" -> {
                currentFolder = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                showAddFolder()
            }
            "IR_ADD_MANUAL" -> {
                chooseIrFolder {
                    currentFolder = it
                    showAddManual()
                }
            }
            "IR_ORGANIZE_DPAD", "IR_LAYOUT_SETTINGS" -> {
                currentFolder = directIrSettingsFolder ?: directIrLibraryRoot ?: allData
                showIrLibrarySettings()
            }
            "IR_IMPORT_FILE" -> {
                currentFolder = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                showFlipperImportDialog()
            }
            "IR_IMPORT_URL" -> {
                currentFolder = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                showImportUrlDialog()
            }
            "LIVE_CAPTURE_BLE" -> chooseCaptureFolder(IrCaptureTransport.BLE)
            "LIVE_CAPTURE_WIFI" -> chooseCaptureFolder(IrCaptureTransport.WIFI)
            "EXPORT_IR_BACKUP" -> exportLauncher.launch("BruceIR_Backup.json")
            "IMPORT_IR_BACKUP" -> importFullDbLauncher.launch("application/json")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setupMainToolBar() {
        binding.btnMacro.setOnClickListener {
            if (moduleAllowed(AppProfileManager.ModuleGroup.MACROS)) {
                startActivity(Intent(this, MacroWorkbenchActivity::class.java))
            }
        }
    }

    private fun setupHeaderActions() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        isEditMode = prefs.getBoolean("edit_lock_mode", false)
        
        adapter.setEditMode(isEditMode)
        binding.btnLock.setImageResource(if (isEditMode) android.R.drawable.ic_partial_secure else android.R.drawable.ic_secure)

        binding.btnSearch.setOnClickListener {
            val et = binding.etSearch
            if (et.visibility == View.VISIBLE) { et.visibility = View.GONE; et.setText(""); refreshList() } else { et.visibility = View.VISIBLE; et.requestFocus() }
        }
        binding.btnViewMode.setOnClickListener {
            val isList = !isListLayout(currentFolder)
            setFolderListLayout(currentFolder, isList)
            Toast.makeText(
                this,
                getString(if (isList) R.string.folder_layout_rows else R.string.folder_layout_grid),
                Toast.LENGTH_SHORT
            ).show()
            refreshList()
        }
        binding.btnViewMode.contentDescription = getString(R.string.folder_layout_customize_hint)
        binding.btnLock.setOnClickListener {
            isEditMode = !isEditMode
            prefs.edit().putBoolean("edit_lock_mode", isEditMode).apply()
            adapter.setEditMode(isEditMode)
            (it as ImageButton).setImageResource(if (isEditMode) android.R.drawable.ic_partial_secure else android.R.drawable.ic_secure)
            binding.btnToggleMenuEdit.text = if (isEditMode) "🔒 Zablokuj edycję menu" else "🔓 Tryb edycji menu (Przestawianie tabel)"
            binding.btnToggleMenuEdit.setTextColor(Color.parseColor(if (isEditMode) "#E53935" else "#43C59E"))
            Toast.makeText(
                this,
                if (isEditMode) "Tryb edycji: przeciągaj i przytrzymuj kategorie menu" else "Tryb edycji wyłączony",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.btnToggleMenuEdit.text = if (isEditMode) "🔒 Zablokuj edycję menu" else "🔓 Tryb edycji menu (Przestawianie tabel)"
        binding.btnToggleMenuEdit.setTextColor(Color.parseColor(if (isEditMode) "#E53935" else "#43C59E"))
        binding.btnToggleMenuEdit.setOnClickListener {
            isEditMode = !isEditMode
            prefs.edit().putBoolean("edit_lock_mode", isEditMode).apply()
            adapter.setEditMode(isEditMode)
            binding.btnLock.setImageResource(if (isEditMode) android.R.drawable.ic_partial_secure else android.R.drawable.ic_secure)
            binding.btnToggleMenuEdit.text = if (isEditMode) "🔒 Zablokuj edycję menu" else "🔓 Tryb edycji menu (Przestawianie tabel)"
            binding.btnToggleMenuEdit.setTextColor(Color.parseColor(if (isEditMode) "#E53935" else "#43C59E"))
            Toast.makeText(
                this,
                if (isEditMode) "Tryb edycji włączony: przeciągaj tabele w menu głównym" else "Tryb edycji zablokowany",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.btnTvBGone.setOnClickListener {
            if (moduleAllowed(AppProfileManager.ModuleGroup.IR)) showTvBGoneMenu()
        }
        binding.btnRemoteMirror.setOnClickListener {
            showBruceMenu()
        }
        binding.btnRemoteMirror.setOnLongClickListener {
            startActivity(Intent(this, RemoteConsoleActivity::class.java))
            true
        }
        binding.ivLogo.setOnClickListener { showManagementMenu() }
        binding.ivLogo.setOnLongClickListener {
            performBruceHandshake { message, _ ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            true
        }
        binding.btnHomeCheck.setOnClickListener {
            it.isEnabled = false
            binding.tvHomeBruceStatus.setText(R.string.home_connection_checking)
            performBruceHandshake { message, successful ->
                lastBruceConnectionMessage = message
                lastBruceConnectionSuccessful = successful
                updateHomeStatus()
                it.isEnabled = true
            }
        }
        binding.btnHomeLiveCapture.setOnClickListener {
            if (moduleAllowed(AppProfileManager.ModuleGroup.IR)) {
                chooseCaptureFolder(IrCaptureTransport.BLE)
            }
        }
        binding.fabAdd.setOnClickListener { showAddFolder() }
        // Keep the Wi-Fi tools reachable from the home screen. The previous
        // direct scanner action hid the WPA cracker and PCAP converter behind
        // an unreferenced management dialog.
        binding.fabWifi.setOnClickListener {
            if (moduleAllowed(AppProfileManager.ModuleGroup.NETWORK)) showWifiAuditSubmenu()
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterList(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun updateStatusDot() {
        when (transmitter.currentMode) {
            IrTransmitter.Mode.WIFI -> binding.vStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            IrTransmitter.Mode.USB -> binding.vStatusDot.setBackgroundResource(R.drawable.status_dot_blue)
            IrTransmitter.Mode.INTERNAL -> binding.vStatusDot.setBackgroundResource(R.drawable.status_dot_red)
            IrTransmitter.Mode.BRUCE_BLE -> binding.vStatusDot.setBackgroundResource(R.drawable.status_dot_blue)
        }
    }

    private fun updateHomeStatus() {
        val mode = transmitter.currentMode
        binding.tvHomeIrStatus.text = when (mode) {
            IrTransmitter.Mode.INTERNAL -> getString(
                if (transmitter.hasInternalIr()) R.string.home_ir_phone_ready else R.string.home_ir_phone_missing
            )
            IrTransmitter.Mode.USB -> getString(
                if (transmitter.isUsbDeviceConnected()) R.string.home_ir_usb_ready else R.string.home_ir_usb_missing
            )
            IrTransmitter.Mode.WIFI -> getString(R.string.home_ir_wifi)
            IrTransmitter.Mode.BRUCE_BLE -> "LilyGo Bluetooth: ${BruceBleIrLink.status()}"
        }
        binding.tvHomeBruceStatus.text = lastBruceConnectionMessage
            ?: getString(R.string.home_connection_not_checked)
        val statusColor = when (lastBruceConnectionSuccessful) {
            true -> R.color.accent_green
            false -> R.color.accent_red
            null -> R.color.white
        }
        binding.tvHomeBruceStatus.setTextColor(getColor(statusColor))
    }

    private fun refreshList() {
        updateStatusDot()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val isRoot = currentFolder == allData
        val isIrHub = directIrLibraryMode && currentFolder == directIrLibraryRoot
        val isIrSettings = directIrLibraryMode && currentFolder == directIrSettingsFolder
        maybeAutoOrganizeCurrentRemote()
        val isWorkbenchMenu = isRoot || isIrHub || isIrSettings
        binding.homeStatusCard.visibility = if (isRoot) View.VISIBLE else View.GONE
        binding.fabAdd.visibility =
            if (directIrLibraryMode || currentFolder == directIrLibraryRoot) View.GONE else View.VISIBLE
        // Wi-Fi tools (including WPA Cracker) must remain reachable while the
        // home screen is focused on the IR pilot library.
        // Widoczność pasków narzędzi zależy też od aktywnego profilu (np. 1111 bez Bruce).
        val bruceAllowed = AppProfileManager.isModuleAvailable(this, AppProfileManager.ModuleGroup.BRUCE)
        val networkAllowed = AppProfileManager.isModuleAvailable(this, AppProfileManager.ModuleGroup.NETWORK)
        val macrosAllowed = AppProfileManager.isModuleAvailable(this, AppProfileManager.ModuleGroup.MACROS)
        val irAllowed = AppProfileManager.isModuleAvailable(this, AppProfileManager.ModuleGroup.IR)
        binding.fabWifi.visibility =
            if (directIrLibraryMode || !networkAllowed) View.GONE else View.VISIBLE
        binding.btnRemoteMirror.visibility =
            if (directIrLibraryMode || !bruceAllowed) View.GONE else View.VISIBLE
        binding.btnMacro.visibility =
            if (directIrLibraryMode || !macrosAllowed) View.GONE else View.VISIBLE
        binding.btnTvBGone.visibility =
            if (directIrLibraryMode || !irAllowed) View.GONE else View.VISIBLE
        binding.btnSmartMacro.visibility = if (directIrLibraryMode && macrosAllowed) View.VISIBLE else View.GONE
        binding.btnSmartMacro.setOnClickListener {
            if (moduleAllowed(AppProfileManager.ModuleGroup.MACROS)) {
                showFolderSmartMacroDialog(currentFolder)
            }
        }
        updateHomeStatus()
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        val iconSize = prefs.getInt("folder_icon_size_dp", 40).coerceIn(24, 56)
        val isGroupedFolder = !isRoot &&
            !isIrHub &&
            currentFolder.items.none { it is Command } &&
            currentFolder.items.any { it is IrFolder }
        val columns = when {
            isIrHub || isIrSettings -> 1
            isRoot -> 2
            else -> prefs.getInt("folder_grid_columns", 3).coerceIn(2, 4)
        }

        val isRemoteSelector = isWorkbenchMenu || currentFolder.items.any { it is IrFolder }
        val isList = if (isRemoteSelector) true else isListLayout(currentFolder)

        adapter.setRootMenu(isWorkbenchMenu)
        adapter.setGroupedFolderMenu(isGroupedFolder)
        adapter.setCarouselMode(false)
        adapter.setListView(isList)
        adapter.setIconSizeDp(iconSize)
        binding.recyclerView.layoutManager = if (isList) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, columns)
        }

        adapter.updateList(currentFolder.items)
        
        binding.tvHeaderTitle.text = when {
            isRoot -> getString(R.string.home_lab_title)
            isIrHub -> getString(R.string.home_category_ir_title)
            else -> currentFolder.name
        }
        binding.tvHeaderTitle.maxLines = 1
        binding.tvHeaderTitle.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        binding.tvHeaderTitle.marqueeRepeatLimit = -1
        binding.tvHeaderTitle.isSelected = true
        binding.tvHeaderTitle.contentDescription =
            when {
                isRoot -> getString(R.string.home_lab_title)
                isIrHub -> getString(R.string.home_category_ir_title)
                else -> folderPathFromRoot(currentFolder).joinToString(" › ")
            }
        binding.tvHeaderTitle.setOnClickListener {
            if (!isRoot && !isIrHub) {
                AlertDialog.Builder(this)
                    .setTitle(currentFolder.name)
                    .setMessage(folderPathFromRoot(currentFolder).joinToString(" › "))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        binding.btnViewMode.visibility = if (isWorkbenchMenu) View.GONE else View.VISIBLE
        binding.tvHeaderTitle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        binding.tvHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        binding.tvHeaderTitle.setTextColor(Color.parseColor("#00E5FF"))

        binding.btnViewMode.setImageResource(
            if (isList) android.R.drawable.ic_menu_agenda else android.R.drawable.ic_menu_sort_by_size
        )
    }

    private fun maybeAutoOrganizeCurrentRemote() {
        if (!directIrLibraryMode) return
        val pilotRoot = directIrPilotFolder ?: return
        if (currentFolder === pilotRoot ||
            currentFolder === directIrSettingsFolder ||
            findFolderRoute(pilotRoot, currentFolder) == null
        ) return
        val route = findFolderRoute(pilotRoot, currentFolder).orEmpty()
        if (route.any(::isSystemRemoteFolder)) return
        if (currentFolder.items.none {
                it is Command && it.name.isNotBlank() && it.iconName != "spacer" &&
                    it.frequency > 0 && (it.pattern.isNotEmpty() || it.protocol != null)
            }
        ) return

        val globalAutoOrganize = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("global_dpad_organize", true)
        if (globalAutoOrganize && !currentFolder.isDpadOrganized) {
            currentFolder.isDpadOrganized = true
            if (!organizeFolderLayout(currentFolder, showToast = false)) {
                save { runOnUiThread { refreshList() } }
            }
        }
    }

    private fun buildLinuxPath(folder: IrFolder): String {
        val pathList = mutableListOf<String>()
        var curr: IrFolder? = folder
        while (curr != null && curr != allData) {
            val nameClean = curr.name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim().replace(' ', '_')
            pathList.add(0, nameClean.ifEmpty { "FOLDER" })
            curr = findParent(allData, curr)
        }
        return "~/" + pathList.joinToString("/")
    }

    private fun setupRemotes() {
        val rv = binding.recyclerView
        adapter = CommandAdapter({ action, item, pos ->
            when (action) {
                CommandAdapter.ActionType.DELETE -> {
                    if (currentFolder !== directIrSettingsFolder &&
                        item != recentFolder && item != downloadedFolder
                    ) confirmAction(R.string.confirm_delete_msg) {
                        currentFolder.items.removeAt(pos)
                        save()
                        refreshList()
                    }
                }
                CommandAdapter.ActionType.EDIT -> {
                    if (currentFolder !== directIrSettingsFolder &&
                        item != recentFolder && item != downloadedFolder
                    ) {
                        if (item is Command) showEditCmd(item, pos)
                        else if (item is IrFolder) showEditFolder(item, pos)
                    }
                }
                CommandAdapter.ActionType.OPEN -> {
                    handleItemOpen(item)
                }
                CommandAdapter.ActionType.MOVE -> showMoveDialog(item, pos)
                CommandAdapter.ActionType.ADD_TO_MACRO -> if (item is Command) showMacroPickerForCommand(item)
            }
        }, { cmd ->
            val pattern = cmd.getEffectivePattern()
            if (pattern.contentEquals(intArrayOf(100, 100)) || cmd.frequency == 0) {
                handleSystemCommandClick(cmd)
            } else {
                transmitter.transmit(cmd.frequency, pattern)
                addToRecent(cmd)
                if (currentFolder == recentFolder) refreshList()
            }
        })
        
        rv.adapter = adapter
    }

    private fun showMacroPickerForCommand(cmd: Command) {
        val macros = macroManager.getAllMacros()
        val options = mutableListOf<String>()
        options.add("+ CREATE NEW MACRO")
        macros.forEach { options.add(it.name) }

        AlertDialog.Builder(this)
            .setTitle("Add '${cmd.name}' to:")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreateMacroWithCommand(cmd)
                } else {
                    val macro = macros[which - 1]
                    macro.commands.add(copyForMacro(cmd))
                    macroManager.updateMacro(macro)
                    Toast.makeText(this, "Added to ${macro.name}", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showCreateMacroWithCommand(cmd: Command) {
        val input = EditText(this).apply { hint = "New Macro Name" }
        AlertDialog.Builder(this)
            .setTitle("New Macro")
            .setView(input)
            .setPositiveButton("Create & Add") { _, _ ->
                val name = input.text.toString().uppercase()
                if (name.isNotEmpty()) {
                    val newMacro = MacroSet(name)
                    newMacro.commands.add(copyForMacro(cmd))
                    macroManager.updateMacro(newMacro)
                    Toast.makeText(this, "Created $name and added ${cmd.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyForMacro(command: Command): Command = command.copy().apply {
        sourceRemotePath = command.sourceRemotePath
            ?: if (currentFolder !== recentFolder) {
                IrCommandOrigin.findFolderPath(allData, currentFolder)
            } else {
                null
            }
            ?: IrCommandOrigin.findCommandPath(allData, command)
    }

    private fun save(onDone: (() -> Unit)? = null) { BruceUtils.saveAllData(this, allData, onDone) }
    private fun addToRecent(cmd: Command) {
        recentFolder.items.removeAll { it is Command && it.name == cmd.name && it.pattern.contentEquals(cmd.pattern) }
        recentFolder.items.add(0, Command(cmd.name, cmd.frequency, cmd.pattern.copyOf()))
        if (recentFolder.items.size > 10) recentFolder.items.removeAt(recentFolder.items.size - 1)
        save()
    }



    private fun showTransmitterModeDialog() {
        val currentMode = transmitter.currentMode
        val modes = arrayOf(
            "📱 Wbudowane IR telefonu (${if (transmitter.hasInternalIr()) "Obsługiwane ✅" else "Brak ❌"})",
            "🔌 Adapter USB OTG IR (${if (transmitter.isUsbDeviceConnected()) "Podłączony ✅" else "Brak ❌"})",
            "🌐 Wi-Fi Bruce Blaster (HTTP API)",
            "🔵 LilyGo Bruce przez Bluetooth LE"
        )
        val selectedIndex = when (currentMode) {
            IrTransmitter.Mode.INTERNAL -> 0
            IrTransmitter.Mode.USB -> 1
            IrTransmitter.Mode.WIFI -> 2
            IrTransmitter.Mode.BRUCE_BLE -> 3
        }
        AlertDialog.Builder(this)
            .setTitle("Tryb Nadajnika IR / Adapter")
            .setSingleChoiceItems(modes, selectedIndex) { dialog, which ->
                when (which) {
                    0 -> {
                        transmitter.currentMode = IrTransmitter.Mode.INTERNAL
                        Toast.makeText(this, "Wybrano: Wbudowane IR telefonu", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        transmitter.currentMode = IrTransmitter.Mode.USB
                        Toast.makeText(this, "Wybrano: Adapter USB OTG IR", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        transmitter.currentMode = IrTransmitter.Mode.WIFI
                        Toast.makeText(this, "Wybrano: Wi-Fi Bruce Blaster", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        dialog.dismiss()
                        enableBleTransmitter()
                        return@setSingleChoiceItems
                    }
                }
                updateStatusDot()
                updateHomeStatus()
                dialog.dismiss()
            }
            .setNegativeButton("ZAMKNIJ", null)
            .show()
    }

    private fun bleTransmitterPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun enableBleTransmitter() {
        val missing = bleTransmitterPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            bleTransmitterPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        activateBleTransmitter()
    }

    private fun activateBleTransmitter() {
        val bluetoothAdapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "This phone does not support Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }
        transmitter.currentMode = IrTransmitter.Mode.BRUCE_BLE
        updateStatusDot()
        updateHomeStatus()
        if (!bluetoothAdapter.isEnabled) {
            enableBleTransmitterBluetoothLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            BruceBleIrLink.connect(this)
        }
    }

    private fun showIrLibrarySettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val globalDpad = prefs.getBoolean("global_dpad_organize", true)
        val currentColumns = prefs.getInt("folder_grid_columns", 3).coerceIn(2, 4)
        val currentIconSize = prefs.getInt("folder_icon_size_dp", 40).coerceIn(24, 56)
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.ir_settings_view_section)
            textSize = 15f
        })
        val layoutChoice = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val currentListLayout = prefs.getBoolean("folders_grid_layout", true).not()
        val gridChoice = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.folder_layout_grid)
            isChecked = !currentListLayout
        }
        val rowsChoice = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.folder_layout_rows)
            isChecked = currentListLayout
        }
        layoutChoice.addView(gridChoice)
        layoutChoice.addView(rowsChoice)
        layoutChoice.check(if (!currentListLayout) gridChoice.id else rowsChoice.id)
        content.addView(layoutChoice)

        content.addView(TextView(this).apply {
            text = getString(R.string.ir_settings_dpad_section)
            textSize = 15f
            setPadding(0, 18, 0, 0)
        })
        val cbDpad = CheckBox(this).apply {
            text = getString(R.string.ir_settings_dpad)
            isChecked = globalDpad
            setPadding(0, 12, 0, 12)
        }
        content.addView(cbDpad)

        content.addView(TextView(this).apply {
            text = getString(R.string.ir_settings_grid_columns_section)
            textSize = 15f
            setPadding(0, 14, 0, 0)
        })
        val columnsLabel = TextView(this)
        content.addView(columnsLabel)
        val columnsSeek = SeekBar(this).apply {
            max = 2
            progress = currentColumns - 2
            isEnabled = !currentListLayout
        }
        content.addView(columnsSeek)

        val iconSizeLabel = TextView(this)
        content.addView(iconSizeLabel)
        val iconSizeSeek = SeekBar(this).apply {
            max = 32
            progress = currentIconSize - 24
        }
        content.addView(iconSizeSeek)

        fun updateColumnsLabel() {
            columnsLabel.text = getString(
                R.string.folder_grid_columns,
                columnsSeek.progress + 2
            )
        }
        updateColumnsLabel()
        columnsSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateColumnsLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        fun updateIconSizeLabel() {
            iconSizeLabel.text = getString(R.string.folder_icon_size_value, iconSizeSeek.progress + 24)
        }
        updateIconSizeLabel()
        iconSizeSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateIconSizeLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        layoutChoice.setOnCheckedChangeListener { _, checkedId ->
            columnsSeek.isEnabled = checkedId == gridChoice.id
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.ir_settings_icon_size_section)
            textSize = 15f
            setPadding(0, 10, 0, 0)
        }, content.indexOfChild(iconSizeLabel))

        AlertDialog.Builder(this)
            .setTitle(R.string.ir_settings_options)
            .setView(content)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val useListLayout = layoutChoice.checkedRadioButtonId == rowsChoice.id
                val oldDpadState = prefs.getBoolean("global_dpad_organize", true)
                val newDpadState = cbDpad.isChecked
                prefs.edit()
                    .putBoolean("folders_grid_layout", !useListLayout)
                    .putInt("folder_grid_columns", columnsSeek.progress + 2)
                    .putInt("folder_icon_size_dp", iconSizeSeek.progress + 24)
                    .putBoolean("global_dpad_organize", newDpadState)
                    .apply()

                if (oldDpadState != newDpadState) {
                    directIrPilotFolder?.let { pilotRoot ->
                        clearRemoteDpadOverrides(pilotRoot, resetLayout = newDpadState)
                    }
                }
                save { runOnUiThread { refreshList() } }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun clearRemoteDpadOverrides(folder: IrFolder, resetLayout: Boolean) {
        folder.autoDpadOrganize = null
        if (resetLayout) folder.isDpadOrganized = false
        folder.items.filterIsInstance<IrFolder>().forEach {
            if (!isSystemRemoteFolder(it)) clearRemoteDpadOverrides(it, resetLayout)
        }
    }

    private fun isListLayout(folder: IrFolder): Boolean {
        val defaultList = folder == directIrLibraryRoot || folder.isListView
        return !getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("folders_grid_layout", !defaultList)
    }

    private fun setFolderListLayout(folder: IrFolder, isList: Boolean) {
        folder.isListView = isList
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("folders_grid_layout", !isList)
            .apply()
    }

    private fun showDownloadedRemoteManager() {
        val items = downloadedFolder.items.filterNot { item ->
            item is IrFolder && isSystemRemoteFolder(item)
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.ir_downloaded_empty, Toast.LENGTH_LONG).show()
            return
        }

        val selected = BooleanArray(items.size)
        val labels = items.map { item ->
            when (item) {
                is IrFolder -> "📁 ${item.name}"
                is Command -> "● ${item.name}"
                else -> item.toString()
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.ir_settings_downloaded)
            .setMultiChoiceItems(labels, selected) { _, which, checked ->
                selected[which] = checked
            }
            .setPositiveButton(R.string.ir_move_next) { _, _ ->
                val movingItems = items.filterIndexed { index, _ -> selected[index] }
                if (movingItems.isEmpty()) {
                    Toast.makeText(this, R.string.ir_move_select_items, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val pilotRoot = directIrPilotFolder ?: return@setPositiveButton
                val destinations = listOf(pilotRoot) + listRemoteFolders(includeEmptyLeaves = false)
                    .filter { it !== pilotRoot }
                val destinationLabels = destinations.map { folder ->
                    if (folder === pilotRoot) getString(R.string.ir_move_to_pilot_root)
                    else remoteDisplayPath(folder)
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle(R.string.ir_move_choose_destination)
                    .setItems(destinationLabels) { _, destinationIndex ->
                        val destination = destinations[destinationIndex]
                        movingItems.forEach { item -> downloadedFolder.items.removeAll { it === item } }
                        destination.items.addAll(movingItems)
                        destination.isDpadOrganized = false
                        destination.autoDpadOrganize = null
                        save {
                            runOnUiThread {
                                refreshList()
                                Toast.makeText(this, R.string.ir_move_complete, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Pozycja menu z grupą modułu - profil decyduje, czy pozycja jest dostępna. */
    private data class MenuAction(
        val label: String,
        val group: AppProfileManager.ModuleGroup,
        val action: () -> Unit
    )

    private fun showManagementMenu() {
        val entries = listOf(
            MenuAction("📶 Wi-Fi Survey & Skaner", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, WifiSurveyActivity::class.java))
            },
            MenuAction("⚡ Agresywny Skan Wi-Fi (Live)", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, AggressiveWifiScanActivity::class.java))
            },
            MenuAction("🌐 Zaawansowany Skaner LAN", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, AdvancedNetworkScannerActivity::class.java))
            },
            MenuAction("🔐 WPA Cracker (.hc22000)", AppProfileManager.ModuleGroup.NETWORK) { showWpaCrackerDialog() },
            MenuAction("🔄 Konwerter PCAP -> Hashcat", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, PcapConverterActivity::class.java))
            },
            MenuAction("🌐 Bruce Web UI", AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, BruceWebActivity::class.java))
            },
            MenuAction("📁 Transfer Plików LilyGO (Wi-Fi)", AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, BruceFileManagerActivity::class.java))
            },
            MenuAction("💻 Konsola Zdalna (Mirror)", AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, RemoteConsoleActivity::class.java))
            },
            MenuAction("📺 Pilot BLE TV (Emulacja HID)", AppProfileManager.ModuleGroup.WIRELESS) {
                startActivity(Intent(this, BruceBleTvRemoteActivity::class.java))
            },
            MenuAction("📺 TV-B-Gone (Power Off TV)", AppProfileManager.ModuleGroup.IR) {
                executeTvBGoneBlast()
            },
            MenuAction("⌘ Makra & Automatyka", AppProfileManager.ModuleGroup.MACROS) {
                startActivity(Intent(this, MacroWorkbenchActivity::class.java))
            },
            MenuAction("⚙ Ustawienia i Informacje", AppProfileManager.ModuleGroup.CORE) { showInfoDialog() }
        )
        showMenuActions("Zarządzanie BruceIR", entries)
    }

    /** Pokazuje tylko pozycje dozwolone w aktywnym profilu; mapa akcji jest indeksowana tym samym filtrem. */
    private fun showMenuActions(title: String, entries: List<MenuAction>) {
        val visible = entries.filter { AppProfileManager.isModuleAvailable(this, it.group) }
        if (visible.isEmpty()) {
            Toast.makeText(
                this,
                "Brak modułów tej sekcji w profilu: ${AppProfileManager.profileLabel(AppProfileManager.getProfileMode(this))}.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(visible.map { it.label }.toTypedArray()) { _, which ->
                visible.getOrNull(which)?.action?.invoke()
            }
            .setNegativeButton("ZAMKNIJ", null)
            .show()
    }

    private fun showWifiAuditSubmenu() {
        val entries = listOf(
            MenuAction("📶 Wi-Fi Survey", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, WifiSurveyActivity::class.java))
            },
            MenuAction("⚡ Agresywny Skan Wi-Fi", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, AggressiveWifiScanActivity::class.java))
            },
            MenuAction("🌐 Zaawansowany Skaner LAN", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, AdvancedNetworkScannerActivity::class.java))
            },
            MenuAction("🔐 WPA Cracker (.hc22000)", AppProfileManager.ModuleGroup.NETWORK) {
                showWpaCrackerDialog()
            },
            MenuAction("🔄 Konwerter PCAP -> Hashcat", AppProfileManager.ModuleGroup.NETWORK) {
                startActivity(Intent(this, PcapConverterActivity::class.java))
            },
            MenuAction("📡 Skaner Celów Wi-Fi", AppProfileManager.ModuleGroup.NETWORK) {
                requestPhoneWifiTargets()
            }
        )
        showMenuActions("⚡ Audyt Wi-Fi & Cracker", entries)
    }

    private fun showBruceMenu() {
        val entries = listOf(
            MenuAction("🌐 Bruce Web UI", AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, BruceWebActivity::class.java))
            },
            MenuAction("📁 Transfer Plików (Wi-Fi)", AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, BruceFileManagerActivity::class.java))
            },
            MenuAction("💻 Konsola Zdalna (Mirror)", AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, RemoteConsoleActivity::class.java))
            },
            MenuAction("📺 Pilot BLE TV (Emulacja HID)", AppProfileManager.ModuleGroup.WIRELESS) {
                startActivity(Intent(this, BruceBleTvRemoteActivity::class.java))
            },
            MenuAction("📺 TV-B-Gone (Power Off TV)", AppProfileManager.ModuleGroup.IR) { showTvBGoneMenu() },
            MenuAction("🧪 Moduły LilyGO & Capture", AppProfileManager.ModuleGroup.BRUCE) { showLilyGoModulesMenu() }
        )
        showMenuActions("Moduły LilyGO / Bruce", entries)
    }

    private fun showLilyGoModulesMenu() {
        val entries = listOf(
            MenuAction(getString(R.string.workbench_tool_modules), AppProfileManager.ModuleGroup.BRUCE) {
                startActivity(Intent(this, RemoteConsoleActivity::class.java).putExtra("show_modules", true))
            },
            MenuAction(getString(R.string.workbench_tool_live_capture_ble), AppProfileManager.ModuleGroup.IR) {
                chooseCaptureFolder(IrCaptureTransport.BLE)
            },
            MenuAction(getString(R.string.workbench_tool_live_capture_wifi), AppProfileManager.ModuleGroup.IR) {
                chooseCaptureFolder(IrCaptureTransport.WIFI)
            }
        )
        showMenuActions("Moduły i Przechwytywanie", entries)
    }

    private fun showTvBGoneMenu() {
        val ops = arrayOf(
            "🚀 EXECUTE TV-B-GONE BLAST (Wyłącz Wszystkie TV)",
            "📁 Dodaj Folder TV-B-GONE do Moich Pilotów"
        )
        AlertDialog.Builder(this)
            .setTitle("📺 TV-B-GONE Super-Module")
            .setItems(ops) { _, which ->
                when (which) {
                    0 -> executeTvBGoneBlast()
                    1 -> addTvBGoneRemote()
                }
            }
            .setNegativeButton("ANULUJ", null)
            .show()
    }

    private fun addTvBGoneRemote() {
        val library = directIrPilotFolder ?: directIrLibraryRoot ?: allData
        if (library.items.filterIsInstance<IrFolder>().any { it.name.contains("TV-B-GONE", ignoreCase = true) }) {
            Toast.makeText(this, "Folder TV-B-GONE jest już w bibliotece pilotów.", Toast.LENGTH_SHORT).show()
            return
        }
        library.items.add(TvBGoneEngine.getTvBGoneFolder())
        save { runOnUiThread { refreshList() } }
        Toast.makeText(this, "Dodano folder TV-B-GONE do pilotów!", Toast.LENGTH_SHORT).show()
    }

    private fun executeTvBGoneBlast() {
        val tvCodes = TvBGoneEngine.getUniversalTvCodes()
        var isBlasting = true

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("📺 TV-B-GONE BLAST IN PROGRESS")
            .setMessage("Wysyłanie sekwencyjne sygnałów wyłączania TV...\n\nSygnały: 0 / ${tvCodes.size}")
            .setNegativeButton("ZATRZYMAJ") { _, _ -> isBlasting = false }
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            for ((index, cmd) in tvCodes.withIndex()) {
                if (!isBlasting) break
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("Wysyłanie: ${cmd.name}\nProgress: ${index + 1} / ${tvCodes.size}")
                }
                transmitter.transmit(cmd.frequency, cmd.pattern)
                delay(300)
            }
            withContext(Dispatchers.Main) {
                if (progressDialog.isShowing) progressDialog.dismiss()
                Toast.makeText(this@MainActivity, "Zakończono wysyłanie sekwencji TV-B-Gone!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleAppWebFileServer() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (AppWebFileServerController.isRunning()) {
            AppWebFileServerController.stop()
            prefs.edit().putBoolean("web_hub_enabled", false).apply()
            Toast.makeText(this, "Zatrzymano serwer Web File Hub", Toast.LENGTH_SHORT).show()
        } else {
            AppWebFileServerController.start(this)
            prefs.edit().putBoolean("web_hub_enabled", true).apply()
            val phoneIp = getLocalIpAddress()
            val port = AppWebFileServerController.PORT
            val token = AppWebFileServerController.accessToken()
            AlertDialog.Builder(this)
                .setTitle("🌐 Web File Hub Aktywny")
                .setMessage(
                    "Otwórz na komputerze w tej samej sieci (link zawiera token sesji):\n\n" +
                        "http://bruceir.local:$port/?token=$token\n" +
                        "http://$phoneIp:$port/?token=$token\n\n" +
                        "Bez tokena serwer odrzuca żądania. Token jest ważny do zatrzymania huba."
                )
                .setPositiveButton("OK", null)
                .setNegativeButton("ZATRZYMAJ SERWER") { _, _ ->
                    AppWebFileServerController.stop()
                    prefs.edit().putBoolean("web_hub_enabled", false).apply()
                    Toast.makeText(this, "Serwer Web zatrzymany", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun importAndSelectHcFile(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val fileName = getUriFileName(uri) ?: "capture.hc22000"
            val destFile = File(filesDir, fileName)
            destFile.writeBytes(bytes)
            selectedHcFileForCracking = destFile
            Toast.makeText(this, "Załadowano plik .hc22000: ${destFile.name}", Toast.LENGTH_SHORT).show()
            showWpaCrackerDialog()
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd odczytu pliku: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importAndSelectWordlistFile(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val fileName = getUriFileName(uri) ?: "wordlist.txt"
            val destFile = File(filesDir, fileName)
            destFile.writeBytes(bytes)
            selectedWordlistForCracking = destFile
            Toast.makeText(this, "Załadowano słownik: ${destFile.name}", Toast.LENGTH_SHORT).show()
            showWpaCrackerDialog()
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd odczytu pliku: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUriFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        return name ?: uri.path?.substringAfterLast('/')
    }

    private var activeCracker: WpaWordlistCracker? = null
    private var selectedHcFileForCracking: File? = null
    private var selectedWordlistForCracking: File? = null

    private fun showWpaCrackerDialog() {
        if (WpaCrackerStatusManager.isRunning) {
            showActiveCrackerProgressDialog()
            return
        }

        val checkpoint = WpaWordlistCracker.getCheckpoint(this)
        if (checkpoint != null) {
            val checkpointHcFile = File(filesDir, checkpoint.hcFileName)
            val checkpointWordlistFile = File(filesDir, checkpoint.wordlistFileName)
            if (checkpointHcFile.exists() && checkpointWordlistFile.exists()) {
                AlertDialog.Builder(this)
                    .setTitle("▶️ Wykryto wstrzymaną sesję łamania WPA")
                    .setMessage(
                        "Plik: ${checkpoint.hcFileName}\n" +
                        "Słownik: ${checkpoint.wordlistFileName}\n" +
                        "Ostatnio przetestowano: ${checkpoint.testedCount} haseł.\n\n" +
                        "Czy chcesz wznowić sesję od miejsca przerwania?"
                    )
                    .setPositiveButton("▶️ WZNOWIĆ (od #${checkpoint.testedCount})") { _, _ ->
                        val hcLine = checkpointHcFile.readLines().firstOrNull { it.startsWith("WPA*") }
                        if (hcLine != null) {
                            startCrackingExecution(
                                checkpointHcFile,
                                hcLine,
                                checkpointWordlistFile,
                                checkpoint.useBest64Rules,
                                startFromCount = checkpoint.testedCount
                            )
                        }
                    }
                    .setNegativeButton("🔄 ZACZNIJ OD NOWA") { _, _ ->
                        WpaWordlistCracker.clearCheckpoint(this)
                        showWpaCrackerDialog()
                    }
                    .setNeutralButton("ANULUJ", null)
                    .show()
                return
            }
        }

        val hcFiles = filesDir.listFiles()?.filter { it.isFile && it.extension == "hc22000" } ?: emptyList()
        val primaryWordlist = ensureDefaultWordlist()

        if (selectedHcFileForCracking == null || !selectedHcFileForCracking!!.exists()) {
            selectedHcFileForCracking = hcFiles.firstOrNull()
        }
        if (selectedWordlistForCracking == null || !selectedWordlistForCracking!!.exists()) {
            selectedWordlistForCracking = primaryWordlist
        }

        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }

        content.addView(TextView(this).apply {
            text = "1. Plik Hashcat (.hc22000):"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4)
        })

        val btnPickHc = Button(this).apply {
            text = "📄 " + (selectedHcFileForCracking?.name ?: "Brak wybranego pliku")
            setOnClickListener {
                val options = mutableListOf<String>()
                options.add("📂 Wybierz plik .hc22000 z pamięci telefonu / SD...")
                hcFiles.forEach { options.add("📄 ${it.name} (${it.length() / 1024} KB)") }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Wybierz plik Hashcat (.hc22000):")
                    .setItems(options.toTypedArray()) { _, idx ->
                        if (idx == 0) {
                            hcFilePickerLauncher.launch("*/*")
                        } else {
                            selectedHcFileForCracking = hcFiles[idx - 1]
                            text = "📄 " + selectedHcFileForCracking?.name
                        }
                    }
                    .show()
            }
        }
        content.addView(btnPickHc)

        content.addView(TextView(this).apply {
            text = "2. Słownik haseł (.txt):"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 4)
        })

        val btnPickWordlist = Button(this).apply {
            text = (if (selectedWordlistForCracking?.name == "kurwayou.txt") "👑 " else "📄 ") + (selectedWordlistForCracking?.name ?: "kurwayou.txt")
            setOnClickListener {
                val txtFiles = filesDir.listFiles()?.filter { it.isFile && it.extension == "txt" } ?: emptyList()
                val allTxtFiles = (listOf(primaryWordlist) + txtFiles).distinctBy { it.name }

                val options = mutableListOf<String>()
                options.add("📂 Wybierz słownik .txt z pamięci telefonu / SD...")
                allTxtFiles.forEach { file ->
                    val isPrimary = file.name == "kurwayou.txt"
                    val badge = if (isPrimary) "👑 " else "📄 "
                    val tag = if (isPrimary) " [Domyślny PL]" else ""
                    options.add("$badge${file.name}$tag  (${file.length() / 1024} KB)")
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Wybierz słownik haseł (.txt):")
                    .setItems(options.toTypedArray()) { _, idx ->
                        if (idx == 0) {
                            wordlistFilePickerLauncher.launch("*/*")
                        } else {
                            selectedWordlistForCracking = allTxtFiles[idx - 1]
                            text = (if (selectedWordlistForCracking?.name == "kurwayou.txt") "👑 " else "📄 ") + selectedWordlistForCracking?.name
                        }
                    }
                    .show()
            }
        }
        content.addView(btnPickWordlist)

        val cbRules = CheckBox(this).apply {
            text = "⚙️ Włącz reguły mutacji best64.rule"
            isChecked = true
            setPadding(0, 12, 0, 4)
        }
        content.addView(cbRules)

        val cbTurbo = CheckBox(this).apply {
            text = "🚀 Tryb Turbo Multi-Core (Wszystkie rdzenie CPU)"
            isChecked = true
            setPadding(0, 4, 0, 16)
        }
        content.addView(cbTurbo)

        AlertDialog.Builder(this)
            .setTitle("⚡ BruceIr WPA Cracker")
            .setView(content)
            .setPositiveButton("⚡ ROZPOCZNIJ ŁAMANIE") { _, _ ->
                val hcFile = selectedHcFileForCracking
                val wordlist = selectedWordlistForCracking ?: primaryWordlist
                if (hcFile != null && hcFile.exists()) {
                    val hcLine = hcFile.readLines().firstOrNull { it.startsWith("WPA*") }
                    if (hcLine != null) {
                        startCrackingExecution(hcFile, hcLine, wordlist, useRules = cbRules.isChecked, useTurbo = cbTurbo.isChecked)
                    } else {
                        Toast.makeText(this, "Niepoprawny format pliku .hc22000", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Wybierz plik .hc22000 do łamania!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ZAMKNIJ", null)
            .show()
    }

    private fun ensureDefaultWordlist(): File {
        val target = File(filesDir, "kurwayou.txt")
        if (!target.exists()) {
            try {
                assets.open("wordlist/kurwayou.txt").use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("BruceIR", "Extracted kurwayou.txt from assets into internal storage")
            } catch (e: Exception) {
                if (!target.exists()) {
                    target.writeText("123456\niw14Fi9j\nqwerty\n123456789\n12345\nzaq12wsx\npolska\n12345678\npassword\nkurwa\nkurwa123\npoland\nadmin123\n00000000\nqwertyuiop")
                }
            }
        }
        return target
    }

    private fun promptRuleEngineAndStartCrack(hcFile: File, hcLine: String, wordlist: File) {
        AlertDialog.Builder(this)
            .setTitle("3. Reguły mutacji (best64.rule)")
            .setMessage("Czy włączyć reguły mutacji haseł Hashcat best64.rule?\n\nGeneruje do 64 wariantów dla każdego słowa (cyfry, wielkie litery, odwrócenia).")
            .setPositiveButton("TAK (best64.rule)") { _, _ ->
                startCrackingExecution(hcFile, hcLine, wordlist, useRules = true)
            }
            .setNegativeButton("NIE (zwykły słownik)") { _, _ ->
                startCrackingExecution(hcFile, hcLine, wordlist, useRules = false)
            }
            .show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "WPA Cracker Notifications"
            val descriptionText = "Notifications when WPA password cracking finishes"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel("wpa_cracker_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun showCrackerSystemNotification(title: String, message: String, isSuccess: Boolean) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            val builder = androidx.core.app.NotificationCompat.Builder(this, "wpa_cracker_channel")
                .setSmallIcon(if (isSuccess) R.drawable.ic_wifi else android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            val notificationManager = androidx.core.app.NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(if (isSuccess) 9901 else 9902, builder.build())
            }
        } catch (e: Exception) {
            Log.e("BruceIR", "Error showing notification: ${e.message}")
        }
    }

    private fun showCrackerOngoingNotification(title: String, message: String, progressInt: Int = 0) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            val builder = androidx.core.app.NotificationCompat.Builder(this, "wpa_cracker_channel")
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle(title)
                .setContentText(message)
                .setProgress(100, progressInt, false)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)

            val notificationManager = androidx.core.app.NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(9900, builder.build())
            }
        } catch (_: Exception) {}
    }

    private fun cancelCrackerOngoingNotification() {
        try {
            val notificationManager = androidx.core.app.NotificationManagerCompat.from(this)
            notificationManager.cancel(9900)
        } catch (_: Exception) {}
    }

    private fun showActiveCrackerProgressDialog() {
        val density = resources.displayMetrics.density
        val statusDialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = WpaCrackerStatusManager.currentPercent.toInt()
            setPadding(0, 12, 0, 16)
        }
        val tvStatus = TextView(this).apply {
            text = "Inicjalizacja widoku BruceIr WPA Cracker..."
            textSize = 14f
        }
        statusDialogView.addView(progressBar)
        statusDialogView.addView(tvStatus)

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("⚡ BruceIr WPA Cracker (Praca w tle)")
            .setView(statusDialogView)
            .setNeutralButton("MINIMALIZUJ (PRACA W TLE)") { dialog, _ ->
                WpaCrackerStatusManager.onProgressUpdateListener = null
                dialog.dismiss()
            }
            .setNegativeButton("ZATRZYMAJ (STOP)") { _, _ ->
                WpaCrackerStatusManager.onProgressUpdateListener = null
                WpaCrackerService.stopCracking(this)
                cancelCrackerOngoingNotification()
            }
            .setCancelable(false)
            .create()

        progressDialog.show()

        fun updateUi(percent: Double, tested: Long, total: Long, speed: Long, word: String) {
            val remainingCandidates = (total - tested).coerceAtLeast(0)
            val etaSec = if (speed > 0) remainingCandidates / speed else 0L

            val etaText = if (etaSec > 0) {
                val mins = etaSec / 60
                val secs = etaSec % 60
                if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            } else "..."

            val filled = (percent / 10).toInt().coerceIn(0, 10)
            val bar = "█".repeat(filled) + "░".repeat(10 - filled)

            progressBar.progress = percent.toInt()
            val percentStr = String.format(java.util.Locale.US, "%.1f", percent)
            tvStatus.text = buildString {
                appendLine("Postęp: $percentStr%  [$bar]")
                appendLine("Przetestowano: $tested / $total")
                appendLine("Prędkość: $speed H/s")
                appendLine("Szacowany czas (ETA): ~$etaText")
                appendLine("Testowane słowo: $word")
            }
        }

        updateUi(
            WpaCrackerStatusManager.currentPercent,
            WpaCrackerStatusManager.currentTestedCount,
            WpaCrackerStatusManager.currentTotalCandidates,
            WpaCrackerStatusManager.currentSpeedHs,
            WpaCrackerStatusManager.currentWord
        )

        WpaCrackerStatusManager.onProgressUpdateListener = { percent, tested, total, speed, word ->
            runOnUiThread {
                if (WpaCrackerStatusManager.isRunning && progressDialog.isShowing) {
                    updateUi(percent, tested, total, speed, word)
                } else if (!WpaCrackerStatusManager.isRunning && progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    private fun startCrackingExecution(
        hcFile: File,
        hcLine: String,
        wordlist: File,
        useRules: Boolean,
        useTurbo: Boolean = true,
        startFromCount: Long = 0L
    ) {
        WpaCrackerService.startCracking(this, hcLine, wordlist, hcFile.name, useRules, useTurbo, startFromCount)
        showActiveCrackerProgressDialog()
    }

    private fun showWifiInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi Scanner & Audit Tools - Pomoc & Info")
            .setMessage(
                "• Moc % vs dBm:\n" +
                "Moc sygnału Wi-Fi w powietrzu mierzy się w dBm (wartości ujemne).\n" +
                "Im wartość jest BAZOWO bliższa zeru, tym sygnał jest silniejszy:\n" +
                "  -30 do -50 dBm = 100% (Perfekcyjny zasięg)\n" +
                "  -60 do -70 dBm = 65-80% (Dobry)\n" +
                "  -80 do -90 dBm = 15-35% (Słaby)\n\n" +
                "• Wykrywanie podłączonych użytkowników:\n" +
                "W pasywnym skanowaniu ramki Beacon rozgłaszane przez punkty AP podają SSID, BSSID i kanał, ale nie podają liczby podłączonych telefonów (klientów STA).\n" +
                "Do zliczania klientów potrzebny jest sprzętowy tryb monitora (Monitor Mode / Sniffing).\n\n" +
                "• Web File Hub (http://phone_ip:8080):\n" +
                "Lokalny serwer w aplikacji pozwalający pobrać pliki .hc22000 bezpośrednio na komputer do łamania w Hashcat!"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestPhoneWifiTargets() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            waitingForWifiScanPermission = true
            wifiLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scanPhoneWifiTargets()
    }

    @SuppressLint("MissingPermission")
    private fun scanPhoneWifiTargets(showToast: Boolean = false) {
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
        if (showToast) {
            Toast.makeText(this, "Scanning nearby Wi-Fi targets...", Toast.LENGTH_SHORT).show()
        }

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
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
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
        Handler(Looper.getMainLooper()).postDelayed(timeout, 12000L)
    }

    private fun updateWifiTargetsUi(results: List<ScanResult>, title: String) {
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

    private class WifiTargetAdapter(
        context: Context,
        private val getFormattedRow: (ScanResult) -> CharSequence
    ) : ArrayAdapter<ScanResult>(context, android.R.layout.simple_list_item_1, ArrayList()) {

        fun setScanResults(newResults: List<ScanResult>) {
            setNotifyOnChange(false)
            clear()
            addAll(newResults)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val result = getItem(position)
            if (result != null && view is TextView) {
                view.text = getFormattedRow(result)
            }
            return view
        }
    }

    private fun buildWifiRowString(result: ScanResult): CharSequence {
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
        val percent = WifiSignalUtils.signalPercent(result.level)
        val gauge = WifiSignalUtils.signalGauge(percent)
        val distanceStr = WifiSignalUtils.estimatedDistanceMeters(result.level, result.frequency)

        return if (wifiColorEnabled) {
            val colorHex = when {
                percent >= 80 -> "#4CAF50"
                percent >= 50 -> "#FF9800"
                percent >= 20 -> "#FF5722"
                else -> "#F44336"
            }
            val dot = when {
                percent >= 80 -> "🟢"
                percent >= 50 -> "🟧"
                percent >= 20 -> "🟥"
                else -> "⚪"
            }
            val htmlText = "<b>$ssid</b>  <font color='#888888'>[~$distanceStr]</font><br/>" +
                    "<small>$security | Ch ${WifiSignalUtils.channel(result.frequency)} | " +
                    "<font color='$colorHex'>$dot $gauge (${result.level} dBm)</font></small>"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(htmlText)
            }
        } else {
            "$ssid  [~$distanceStr]\n$security  |  Ch ${WifiSignalUtils.channel(result.frequency)}  |  $gauge  (${result.level} dBm)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPhoneWifiTargets(results: List<ScanResult>, title: String) {
        val sortedResults = results.sortedByDescending { it.level }
        sortedWifiResults = sortedResults
        val newRows = sortedResults.map { buildWifiRowString(it) }
            .ifEmpty { listOf<CharSequence>("No Wi-Fi networks found.") }

        val colorText = if (wifiColorEnabled) "🎨 ON" else "🎨 OFF"

        wifiDetailDialog?.dismiss()
        wifiDetailDialog = null

        val existingDialog = wifiTargetDialog
        val existingAdapter = wifiTargetAdapter
        if (existingDialog != null && existingDialog.isShowing && existingAdapter != null) {
            existingDialog.setTitle(title)
            existingAdapter.setScanResults(sortedResults)
            return
        }

        val customAdapter = WifiTargetAdapter(this) { buildWifiRowString(it) }
        customAdapter.setScanResults(sortedResults)
        wifiTargetAdapter = customAdapter

        val listView = ListView(this).apply {
            adapter = customAdapter
            setOnItemClickListener { _, _, position, _ ->
                if (position in sortedWifiResults.indices) {
                    val target = sortedWifiResults[position]
                    selectedWifiBssid = target.BSSID
                    showWifiTargetDetails(target)
                }
            }
        }
        wifiTargetListView = listView

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(listView)
            .setPositiveButton("REFRESH", null)
            .setNeutralButton("COLORS: $colorText", null)
            .setNegativeButton("CLOSE") { _, _ ->
                wifiAutoRefreshIntervalMs = 0L
                wifiAutoRefreshRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            }

        wifiTargetDialog?.dismiss()
        val newDialog = dialogBuilder.create()
        newDialog.setOnShowListener {
            newDialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                scanPhoneWifiTargets(showToast = false)
            }
            newDialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                wifiColorEnabled = !wifiColorEnabled
                val newColorText = if (wifiColorEnabled) "🎨 ON" else "🎨 OFF"
                newDialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.text = "COLORS: $newColorText"
                showPhoneWifiTargets(lastWifiScanResults, title)
            }
        }
        newDialog.setOnDismissListener {
            wifiAutoRefreshIntervalMs = 0L
            wifiAutoRefreshRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            wifiAutoRefreshRunnable = null
            clearPhoneWifiScan()
            wifiTargetAdapter = null
            wifiTargetListView = null
            wifiTargetDialog = null
        }
        wifiTargetDialog = newDialog
        newDialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun showWifiTargetDetails(target: ScanResult) {
        val ssid = target.SSID
            .filter { it >= ' ' && it != '\u007f' }
            .takeIf { it.isNotBlank() } ?: "(hidden network)"
        val percent = WifiSignalUtils.signalPercent(target.level)
        val gauge = WifiSignalUtils.signalGauge(percent)
        val quality = WifiSignalUtils.qualityText(percent)
        val freqGhz = String.format(java.util.Locale.US, "%.1f GHz", target.frequency / 1000.0)
        val distanceStr = WifiSignalUtils.estimatedDistanceMeters(target.level, target.frequency)

        val detailsText = buildString {
            appendLine("SSID: $ssid")
            appendLine("BSSID (MAC): ${target.BSSID}")
            appendLine()
            appendLine("Signal Strength:  $gauge")
            appendLine("Level:  ${target.level} dBm  ($quality)")
            appendLine("Est. Distance:  ~$distanceStr (Free-Space Path Loss)")
            appendLine("Channel:  ${WifiSignalUtils.channel(target.frequency)}  ($freqGhz)")
            appendLine("Capabilities:  ${target.capabilities}")
            appendLine()
            appendLine("----------------------------------------------")
            appendLine("ℹ️ Android 9+ removed native WPS PIN API.")
            appendLine("Use 'WPS PIN (Bruce)' below to connect over-the-air using Bruce ESP32 hardware.")
        }

        val existingDetail = wifiDetailDialog
        if (existingDetail != null && existingDetail.isShowing) {
            existingDetail.setTitle("🎯 Target: $ssid")
            existingDetail.setMessage(detailsText)
            return
        }

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle("🎯 Target: $ssid")
            .setMessage(detailsText)
            .setPositiveButton("🔑 WPS PIN (BRUCE)") { _, _ ->
                promptWpsPinConnect(ssid)
            }
            .setNeutralButton("REFRESH NOW", null)
            .setNegativeButton("BACK") { _, _ ->
                selectedWifiBssid = null
                wifiDetailDialog?.dismiss()
                wifiDetailDialog = null
                showPhoneWifiTargets(lastWifiScanResults, "Nearby Wi-Fi targets")
            }

        wifiDetailDialog?.dismiss()
        val newDialog = dialogBuilder.create()
        newDialog.setOnShowListener {
            newDialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                scanPhoneWifiTargets(showToast = false)
            }
        }
        newDialog.setOnDismissListener {
            if (selectedWifiBssid != null && wifiTargetDialog == null) {
                wifiAutoRefreshIntervalMs = 0L
                wifiAutoRefreshRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                wifiAutoRefreshRunnable = null
                clearPhoneWifiScan()
            }
            wifiDetailDialog = null
        }
        wifiDetailDialog = newDialog
        newDialog.show()
    }

    private fun promptWpsPinConnect(ssid: String) {
        val input = EditText(this).apply {
            hint = "e.g. 12345670"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("🔑 WPS PIN Connect (Bruce ESP32)")
            .setMessage("Android 9+ removed native WPS PIN API.\nBruce ESP32 hardware connects over-the-air directly using WPS PIN.\n\nEnter WPS PIN for $ssid:")
            .setView(input)
            .setPositiveButton("CONNECT VIA BRUCE") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.isNotEmpty()) {
                    val intent = Intent(this, RemoteConsoleActivity::class.java).apply {
                        putExtra("run_command", "wifi wps \"$ssid\" $pin")
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
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
        wifiAutoRefreshRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        if (wifiAutoRefreshIntervalMs <= 0) return
        val runnable = Runnable {
            scanPhoneWifiTargets()
        }
        wifiAutoRefreshRunnable = runnable
        Handler(Looper.getMainLooper()).postDelayed(runnable, wifiAutoRefreshIntervalMs)
    }

    private fun showWifiTargetsMessage(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi Targets")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun clearPhoneWifiScan() {
        wifiScanTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        wifiScanTimeout = null
        wifiScanReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        wifiScanReceiver = null
    }

    private fun sortCurrentFolder() {
        currentFolder.items.sortWith(compareBy({ if (it is IrFolder) 0 else 1 }, { if (it is IrItem) it.name else "" }))
        save { runOnUiThread { refreshList() } }
    }

    private fun organizeFolderLayout(
        folder: IrFolder,
        showToast: Boolean = true,
        onDone: (() -> Unit)? = null
    ): Boolean {
        val allCommands = folder.items.filterIsInstance<Command>()
            .filter { it.name.isNotBlank() && it.iconName != "spacer" }
            .toMutableList()
        val nonCommands = folder.items.filter { it !is Command }

        if (allCommands.isEmpty()) {
            return false
        }

        fun normalizedTokens(value: String): List<String> =
            value.uppercase(java.util.Locale.ROOT)
                .replace("Ł", "L")
                .replace("Ś", "S")
                .replace("Ć", "C")
                .replace("Ż", "Z")
                .replace("Ź", "Z")
                .replace("Ń", "N")
                .replace("Ó", "O")
                .replace("▲", " UP ")
                .replace("▼", " DOWN ")
                .replace("◄", " LEFT ")
                .replace("►", " RIGHT ")
                .replace("+", " PLUS ")
                .replace("-", " MINUS ")
                .replace("_", " ")
                .replace(Regex("[^A-Z0-9 ]"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)

        fun containsPhrase(tokens: List<String>, phrase: List<String>): Boolean =
            phrase.isNotEmpty() && tokens.windowed(phrase.size).any { it == phrase }

        fun extractMatching(
            keywords: List<String>,
            excludedKeywords: List<String> = emptyList()
        ): Command? {
            val phrases = keywords.map(::normalizedTokens)
            val excludedPhrases = excludedKeywords.map(::normalizedTokens)
            val matchedIndex = allCommands.indexOfFirst { command ->
                val tokens = normalizedTokens(command.name)
                excludedPhrases.none { containsPhrase(tokens, it) } &&
                    phrases.any { containsPhrase(tokens, it) }
            }
            return if (matchedIndex == -1) null else allCommands.removeAt(matchedIndex)
        }

        val dummySpacer = Command("", 0, intArrayOf(), "spacer")

        val upCmd = extractMatching(
            listOf("UP", "GORA", "NAV UP", "TOP", "▲"),
            listOf("VOL", "VOLUME", "CH", "CHANNEL")
        )
        val downCmd = extractMatching(
            listOf("DOWN", "DOL", "NAV DOWN", "BOTTOM", "▼"),
            listOf("VOL", "VOLUME", "CH", "CHANNEL")
        )
        val leftCmd = extractMatching(listOf("LEFT", "LEWO", "NAV LEFT", "◄"))
        val rightCmd = extractMatching(listOf("RIGHT", "PRAWO", "NAV RIGHT", "►"))
        val okCmd = extractMatching(listOf("OK", "ENTER", "SELECT", "WYBIERZ", "SRODEK", "CENTER"))
        val backCmd = extractMatching(listOf("BACK", "EXIT", "WROC", "RETURN", "ESC", "CANCEL"))

        val powerCmd = extractMatching(listOf("POWER", "POWER TOGGLE", "ON OFF", "ON", "OFF", "STANDBY"))
        val volUpCmd = extractMatching(
            listOf("VOL PLUS", "VOLUME PLUS", "VOL UP", "VOLUME UP", "GLOSNOSC PLUS", "GLOSNOSC UP")
        )
        val volDownCmd = extractMatching(
            listOf("VOL MINUS", "VOLUME MINUS", "VOL DOWN", "VOLUME DOWN", "GLOSNOSC MINUS", "GLOSNOSC DOWN")
        )
        val muteCmd = extractMatching(listOf("MUTE", "WYCISZ", "MUTE TOGGLE"))
        val chUpCmd = extractMatching(listOf("CH PLUS", "CHANNEL PLUS", "CH UP", "CHANNEL UP", "KANAL PLUS", "KANAL UP"))
        val chDownCmd = extractMatching(listOf("CH MINUS", "CHANNEL MINUS", "CH DOWN", "CHANNEL DOWN", "KANAL MINUS", "KANAL DOWN"))
        val menuCmd = extractMatching(listOf("MENU", "HOME", "DOM", "INFO", "SOURCE", "INPUT"))

        val hasDpad = upCmd != null || downCmd != null || leftCmd != null ||
            rightCmd != null || okCmd != null || backCmd != null

        if (!hasDpad) {
            if (showToast) {
                Toast.makeText(this, R.string.ir_dpad_no_controls, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val organizedItems = mutableListOf<Any>()

        // Row 0
        organizedItems.add(powerCmd ?: dummySpacer)
        organizedItems.add(upCmd ?: dummySpacer)
        organizedItems.add(menuCmd ?: dummySpacer)

        // Row 1
        organizedItems.add(leftCmd ?: dummySpacer)
        organizedItems.add(okCmd ?: dummySpacer)
        organizedItems.add(rightCmd ?: dummySpacer)

        // Row 2
        organizedItems.add(backCmd ?: dummySpacer)
        organizedItems.add(downCmd ?: dummySpacer)
        organizedItems.add(dummySpacer)

        // Row 3
        organizedItems.add(volUpCmd ?: dummySpacer)
        organizedItems.add(muteCmd ?: dummySpacer)
        organizedItems.add(chUpCmd ?: dummySpacer)

        // Row 4
        organizedItems.add(volDownCmd ?: dummySpacer)
        organizedItems.add(dummySpacer)
        organizedItems.add(chDownCmd ?: dummySpacer)

        while (organizedItems.isNotEmpty() && (organizedItems.last() as? Command)?.iconName == "spacer") {
            organizedItems.removeAt(organizedItems.size - 1)
        }

        organizedItems.addAll(allCommands)
        organizedItems.addAll(nonCommands)

        folder.items.clear()
        folder.items.addAll(organizedItems)

        save {
            runOnUiThread {
                refreshList()
                if (showToast) {
                    Toast.makeText(this, R.string.toast_layout_organized, Toast.LENGTH_SHORT).show()
                }
                onDone?.invoke()
            }
        }
        return true
    }

    private fun chooseCaptureFolder(transport: IrCaptureTransport) {
        val library = directIrPilotFolder ?: allData
        val folders = listRemoteFolders(includeEmptyLeaves = true)
        val labels = folders.map(::remoteDisplayPath).toMutableList()
        labels.add(getString(R.string.ir_capture_create_remote))

        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (transport == IrCaptureTransport.WIFI) {
                        R.string.ir_capture_choose_wifi_remote
                    } else {
                        R.string.ir_capture_choose_ble_remote
                    }
                )
            )
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < folders.size) {
                    startCaptureInFolder(folders[which], transport)
                } else {
                    val input = EditText(this).apply {
                        hint = getString(R.string.ir_capture_remote_name_hint)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.ir_capture_new_remote_title)
                        .setView(input)
                        .setPositiveButton(R.string.ir_capture_create_and_capture) { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isEmpty()) {
                                Toast.makeText(
                                    this,
                                    R.string.ir_capture_remote_name_required,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val folder = IrFolder(name)
                                library.items.add(folder)
                                save {
                                    runOnUiThread {
                                        startCaptureInFolder(folder, transport)
                                    }
                                }
                            }
                        }
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startCaptureInFolder(folder: IrFolder, transport: IrCaptureTransport) {
        when (transport) {
            IrCaptureTransport.BLE -> startBleCapture(folder)
            IrCaptureTransport.WIFI -> startWifiCapture(folder)
        }
    }

    private fun listRemoteFolders(includeEmptyLeaves: Boolean = true): List<IrFolder> {
        val library = directIrPilotFolder ?: return emptyList()
        val result = mutableListOf<IrFolder>()
        fun collect(folder: IrFolder) {
            folder.items.filterIsInstance<IrFolder>().forEach { child ->
                if (isSystemRemoteFolder(child)) return@forEach
                val hasCommands = child.items.any {
                    it is Command && it.name.isNotBlank() && it.iconName != "spacer" &&
                        it.frequency > 0 && (it.pattern.isNotEmpty() || it.protocol != null)
                }
                val hasRemoteChildren = child.items.filterIsInstance<IrFolder>()
                    .any { !isSystemRemoteFolder(it) }
                if (hasCommands || (includeEmptyLeaves && !hasRemoteChildren && child.items.isEmpty())) {
                    result += child
                }
                collect(child)
            }
        }
        collect(library)
        return result
    }

    private fun isSystemRemoteFolder(folder: IrFolder): Boolean =
        folder === recentFolder ||
            folder === downloadedFolder ||
            folder.name.equals("RECENTLY USED", ignoreCase = true) ||
            folder.name.equals("DOWNLOADED", ignoreCase = true)

    private fun remoteDisplayPath(folder: IrFolder): String =
        folderPathFromRoot(folder)
            .filterNot {
                it.equals("PILOTY I KODY IR", ignoreCase = true) ||
                    it.equals("📺 PILOTY & KODY IR", ignoreCase = true) ||
                    it.equals("📺 PILOTY", ignoreCase = true) ||
                    it.equals("DOWNLOADED", ignoreCase = true)
            }
            .joinToString(" › ")

    private fun chooseIrFolder(
        folders: List<IrFolder> = listRemoteFolders(includeEmptyLeaves = true),
        onSelected: (IrFolder) -> Unit
    ) {
        if (folders.isEmpty()) {
            Toast.makeText(this, R.string.ir_capture_no_remote_folders, Toast.LENGTH_LONG).show()
            return
        }
        val labels = folders.map(::remoteDisplayPath).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.workbench_choose_ir_remote)
            .setItems(labels) { _, which ->
                directIrLibraryMode = true
                onSelected(folders[which])
                refreshList()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startBleCapture(targetFolder: IrFolder) {
        navigateToRemoteFolder(targetFolder)
        bleCaptureTargetFolderPath = folderPathFromRoot(targetFolder)
        startActivity(
            Intent(this, RemoteConsoleActivity::class.java)
                .putExtra("EXTRA_START_IR_CAPTURE", true)
        )
    }

    private fun folderPathFromRoot(folder: IrFolder): List<String> {
        val path = mutableListOf<String>()
        var current: IrFolder? = folder
        while (current != null && current !== allData) {
            path.add(current.name)
            current = findParent(allData, current)
        }
        return path.asReversed()
    }

    private fun resolveFolderPath(path: List<String>): IrFolder? {
        var current = allData
        for (name in path) {
            current = current.items.filterIsInstance<IrFolder>().firstOrNull { 
                it.name.equals(name, ignoreCase = true) || 
                it.name.contains(name, ignoreCase = true) || 
                name.contains(it.name, ignoreCase = true) ||
                it.name.filter(Char::isLetterOrDigit).equals(name.filter(Char::isLetterOrDigit), ignoreCase = true)
            } ?: return null
        }
        return current
    }

    private fun navigateToRemoteFolder(folder: IrFolder) {
        val route = findFolderRoute(allData, folder) ?: return
        folderNavigationStack.clear()
        route.dropLast(1).forEach(folderNavigationStack::push)
        directIrLibraryMode = true
        currentFolder = folder
        refreshList()
    }

    private fun returnToMainMenu() {
        folderNavigationStack.clear()
        directIrLibraryMode = false
        currentFolder = allData
        refreshList()
    }

    private fun handleItemOpen(item: Any) {
        if (item is IrFolder) {
            if (BruceFlipperCache.isManagedFolderName(item.name)) {
                openManagedFlipperFolder(item)
                return
            }

            binding.etSearch.setText("")
            binding.etSearch.visibility = View.GONE
            directIrLibraryMode = true
            if (item != currentFolder) {
                folderNavigationStack.push(currentFolder)
                currentFolder = item
                refreshList()
            }
        } else if (item is Command) {
            if (BruceFlipperCache.isPlaceholderCommandName(item.name) ||
                BruceFlipperCache.isErrorCommandName(item.name)
            ) {
                val parent = currentFolder
                if (BruceFlipperCache.isManagedFolderName(parent.name)) {
                    openManagedFlipperFolder(parent, forceReload = true)
                } else {
                    pendingFlipperDbImport = true
                    pickFile.launch("*/*")
                }
                return
            }
            if (item.name.contains("wybrać i zaimportować", true)) {
                pendingFlipperDbImport = false
                pickFile.launch("*/*")
                return
            }
            binding.etSearch.setText("")
            binding.etSearch.visibility = View.GONE
            handleSystemCommandClick(item)
        }
    }

    private fun startWifiCapture(targetFolder: IrFolder) {
        wifiCaptureJob?.cancel()
        wifiCaptureDialog?.dismiss()
        returnToMainMenu()
        val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val waitingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.ir_capture_wifi_wait_title)
            .setMessage(R.string.ir_capture_wifi_wait_message)
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                activeConnection.getAndSet(null)?.disconnect()
                wifiCaptureJob?.cancel()
            }
            .create()
        waitingDialog.setCancelable(false)
        waitingDialog.setCanceledOnTouchOutside(false)
        waitingDialog.show()
        wifiCaptureDialog = waitingDialog

        val captureJob = lifecycleScope.launch {
            var signalCaptured = false
            try {
                val capture = withContext(Dispatchers.IO) {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    val configuredUrl = prefs.getString("bruce_url", "http://bruce.local")
                        ?.trim()
                        .orEmpty()
                    val baseUrl = when {
                        configuredUrl.isBlank() -> "http://bruce.local"
                        configuredUrl.startsWith("http://", ignoreCase = true) ||
                            configuredUrl.startsWith("https://", ignoreCase = true) ->
                            configuredUrl.trimEnd('/')
                        else -> "http://${configuredUrl.trimEnd('/')}"
                    }
                    val username = prefs.getString("bruce_user", "admin").orEmpty()
                    val password = prefs.getString("bruce_pass", "bruce").orEmpty()
                    val captureUrl = URL("$baseUrl/irreceive")
                    Log.i("BruceIR", "Starting Wi-Fi IR capture at ${captureUrl.host}")
                    val connection = captureUrl.openConnection() as HttpURLConnection
                    activeConnection.set(connection)
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 5_000
                        connection.readTimeout = WIFI_IR_CAPTURE_TIMEOUT_MS
                        connection.instanceFollowRedirects = false
                        if (username.isNotEmpty() || password.isNotEmpty()) {
                            val credentials = android.util.Base64.encodeToString(
                                "$username:$password".toByteArray(Charsets.UTF_8),
                                android.util.Base64.NO_WRAP
                            )
                            connection.setRequestProperty("Authorization", "Basic $credentials")
                        }

                        val responseCode = connection.responseCode
                        if (responseCode !in 200..299) {
                            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                                ?.trim()
                                .orEmpty()
                            if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
                                throw IrCaptureNoSignalException()
                            }
                            throw IOException(
                                "Bruce returned HTTP $responseCode" +
                                    if (detail.isEmpty()) "." else ": ${detail.take(160)}"
                            )
                        }
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        activeConnection.compareAndSet(connection, null)
                        connection.disconnect()
                    }
                }
                val parsedCapture = parseBruceIrCapture(capture)
                if (parsedCapture != null) {
                    signalCaptured = true
                    returnToMainMenu()
                    showLiveCaptureDialog(
                        parsedCapture,
                        targetFolder,
                        onRecapture = { startWifiCapture(targetFolder) },
                        onSaved = { startWifiCapture(targetFolder) }
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Could not parse the captured IR signal", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IrCaptureNoSignalException) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.ir_capture_no_signal,
                    Toast.LENGTH_LONG
                ).show()
            } catch (exception: UnknownHostException) {
                Log.e("BruceIR", "Wi-Fi IR capture host could not be resolved", exception)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.ir_capture_host_not_found, exception.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            } catch (exception: IOException) {
                Log.e("BruceIR", "Wi-Fi IR capture request failed", exception)
                Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.ir_capture_failed_detail,
                        exception.message ?: getString(R.string.home_connection_failed)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (exception: SecurityException) {
                Log.e("BruceIR", "Wi-Fi IR capture was blocked", exception)
                Toast.makeText(
                    this@MainActivity,
                    "Wi-Fi IR capture was blocked: ${exception.message ?: "network access denied"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
                if (!signalCaptured) {
                    returnToMainMenu()
                }
                if (wifiCaptureDialog === waitingDialog) {
                    waitingDialog.dismiss()
                    wifiCaptureDialog = null
                }
                if (wifiCaptureJob === coroutineContext[Job]) {
                    wifiCaptureJob = null
                }
            }
        }
        wifiCaptureJob = captureJob
    }

    private fun parseBruceIrCapture(capture: String): String? {
        var frequency = 38000
        var data: String? = null
        for (line in capture.lineSequence()) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "frequency" -> frequency = value.toIntOrNull() ?: frequency
                "data" -> data = value
            }
        }
        val samples = data?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull()?.let { value -> kotlin.math.abs(value) } }
            ?.filter { it > 0 }
            ?: return null
        if (samples.size < 2) return null

        val pattern = JSONArray()
        samples.forEach { pattern.put(it) }
        return JSONObject()
            .put("ir", JSONObject().put("frequency", frequency).put("pattern", pattern))
            .toString()
    }

    private fun confirmAction(msgRes: Int, onConfirm: () -> Unit) { AlertDialog.Builder(this).setTitle("Confirm").setMessage(msgRes).setPositiveButton("OK") { _, _ -> onConfirm() }.setNegativeButton("Cancel", null).show() }
    private fun showAddFolder() { val input = EditText(this); AlertDialog.Builder(this).setTitle("New Folder").setView(input).setPositiveButton("OK") { _, _ -> currentFolder.items.add(IrFolder(input.text.toString().uppercase())); save { runOnUiThread { refreshList() } } }.show() }
    private fun showAddManual() { val input = EditText(this); AlertDialog.Builder(this).setTitle("Manual Add").setView(input).setPositiveButton("OK") { _, _ -> currentFolder.items.add(Command(input.text.toString(), 38000, intArrayOf(100, 100))); save { runOnUiThread { refreshList() } } }.show() }

    private fun showEditCmd(cmd: Command, pos: Int) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        val nameIn = EditText(this).apply { setText(cmd.name); hint = "Button Name" }
        
        val iconRow = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
        }
        val iconDisplay = TextView(this).apply { 
            text = cmd.iconName ?: "❓"
            textSize = 36f
            setPadding(20, 20, 40, 20)
            setBackgroundResource(R.drawable.button_glass_bg)
            setOnClickListener { showEmojiPicker { emoji -> text = emoji; cmd.iconName = emoji } }
        }
        iconRow.addView(iconDisplay)
        iconRow.addView(TextView(this).apply { text = "Tap icon to change" })
        
        // Color Picker Premium
        val colors = arrayOf("#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#546E7A", "#00B8D4", "#00C853", "#FFD600", "#D50000", "#C51162", "#AA00FF")
        val colorGrid = GridLayout(this).apply { 
            columnCount = 6
            setPadding(0, 20, 0, 20)
        }
        var selectedColor = cmd.colorHex ?: "#1E1E1E"
        
        colors.forEach { col ->
            val v = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 80; height = 80
                    setMargins(10, 10, 10, 10)
                }
                setBackgroundColor(Color.parseColor(col))
                setOnClickListener { 
                    selectedColor = col
                    Toast.makeText(context, "Color selected", Toast.LENGTH_SHORT).show()
                }
            }
            colorGrid.addView(v)
        }

        lay.addView(TextView(this).apply { text = "Name" })
        lay.addView(nameIn)
        lay.addView(TextView(this).apply { 
            text = "Icon / Emoji"
            setPadding(0, 20, 0, 10) 
        })
        lay.addView(iconRow)
        lay.addView(TextView(this).apply { 
            text = "Button Color"
            setPadding(0, 20, 0, 10) 
        })
        lay.addView(colorGrid)
        
        // Add to Macro Quick Button
        val btnAddMacro = Button(this).apply { 
            text = "+ ADD TO MACRO"
            setOnClickListener { showMacroPickerForCommand(cmd) }
            setPadding(0, 20, 0, 20)
        }
        lay.addView(btnAddMacro)

        lay.addView(TextView(this).apply { 
            text = "Advanced: RAW Data (Careful!)"
            textSize = 10f
            alpha = 0.5f
            setPadding(0, 40, 0, 0)
        })
        val dataIn = EditText(this).apply { 
            setText(cmd.pattern.joinToString(" "))
            textSize = 10f
        }
        lay.addView(dataIn)

        AlertDialog.Builder(this)
            .setTitle("Edit Button")
            .setView(lay)
            .setPositiveButton("Save") { _, _ -> 
                try { 
                    cmd.name = nameIn.text.toString()
                    cmd.colorHex = selectedColor
                    cmd.pattern = dataIn.text.toString().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray()
                    save { runOnUiThread { adapter.notifyItemChanged(pos) } } 
                } catch (e: Exception) {} 
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmojiPicker(onSelected: (String) -> Unit) {
        val emojis = arrayOf(
            "📺", "🔊", "🔉", "🔇", "⏯️", "⏩", "⏪", "⏹️", "⏺️", "🔆", "🔅", "🔌", "💡", 
            "❄️", "🔥", "🏠", "⚙️", "ℹ️", "🔍", "🔴", "🟢", "🔵", "🟡", "⚪", "⚫", "🔲",
            "⬆️", "⬇️", "⬅️", "➡️", "🆗", "🔙", "🔝", "🔃", "🔄", "🔋", "📶", "⭐", "❤️",
            "🛠️", "🔧", "📱", "💻", "⌨️", "🖱️", "🎮", "🕹️", "📡", "🛰️", "🚀", "🛸", "🚁",
            "🚗", "🚲", "🚪", "🪟", "🔑", "🔓", "🔒", "🔔", "📣", "📻", "🎙️", "🎸", "🎹"
        )
        val grid = GridLayout(this).apply { 
            columnCount = 5
            setPadding(30, 30, 30, 30)
        }
        
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 800)
            addView(grid)
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Icon")
            .setView(scroll)
            .create()
        
        emojis.forEach { emoji ->
            val tv = TextView(this).apply {
                text = emoji
                textSize = 32f
                setPadding(25, 25, 25, 25)
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.button_glass_bg)
                val lp = GridLayout.LayoutParams().apply {
                    setMargins(10, 10, 10, 10)
                }
                layoutParams = lp
                setOnClickListener { 
                    onSelected(emoji)
                    dialog.dismiss()
                }
            }
            grid.addView(tv)
        }
        dialog.show()
    }

    private fun showEditFolder(f: IrFolder, pos: Int) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        val nameIn = EditText(this).apply { setText(f.name); hint = "Folder Name" }
        
        val iconRow = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
        }
        val iconDisplay = TextView(this).apply { 
            text = f.iconName ?: "📁"
            textSize = 36f
            setPadding(20, 20, 40, 20)
            setBackgroundResource(R.drawable.button_glass_bg)
            setOnClickListener { showEmojiPicker { emoji -> text = emoji; f.iconName = emoji } }
        }
        iconRow.addView(iconDisplay)
        iconRow.addView(TextView(this).apply { text = "Tap icon to change" })
        
        val colors = arrayOf("#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#546E7A", "#00B8D4", "#00C853", "#FFD600", "#D50000", "#C51162", "#AA00FF")
        val colorGrid = GridLayout(this).apply { 
            columnCount = 6
            setPadding(0, 20, 0, 20)
        }
        var selectedColor = f.colorHex ?: "#37474F"
        
        colors.forEach { col ->
            val v = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 80; height = 80
                    setMargins(10, 10, 10, 10)
                }
                setBackgroundColor(Color.parseColor(col))
                setOnClickListener { 
                    selectedColor = col
                }
            }
            colorGrid.addView(v)
        }

        lay.addView(TextView(this).apply { text = "Name" })
        lay.addView(nameIn)
        lay.addView(TextView(this).apply { 
            text = "Folder Icon"
            setPadding(0, 20, 0, 10) 
        })
        lay.addView(iconRow)
        lay.addView(TextView(this).apply { 
            text = "Folder Color"
            setPadding(0, 20, 0, 10) 
        })
        lay.addView(colorGrid)

        AlertDialog.Builder(this)
            .setTitle("Edit Folder")
            .setView(lay)
            .setPositiveButton("Save") { _, _ -> 
                try { 
                    f.name = nameIn.text.toString().uppercase()
                    f.colorHex = selectedColor
                    save { runOnUiThread { adapter.notifyItemChanged(pos) } } 
                } catch (e: Exception) {} 
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun filterList(query: String) {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery == "razor" || cleanQuery == "razor1911" || cleanQuery == "keygen" || cleanQuery == "chiptune" || cleanQuery == "fsociety" || cleanQuery == "elliot" || cleanQuery == "mr.robot" || cleanQuery == "hello_friend" || cleanQuery == "5/9" || cleanQuery == "evilcorp") {
            val mrRobotQuotes = listOf(
                "Hello, friend. 🤖 fsociety // Control is an illusion.",
                "Razor 1911 x Mr. Robot - Legendary Hacker / Chiptune 🏴‍☠️",
                "Hello friend. Maybe I should give you a name, but that's a slippery slope...",
                "World-Class Hacking & Electrical Operations Enabled. 🤖"
            )
            Toast.makeText(this, mrRobotQuotes.random(), Toast.LENGTH_LONG).show()
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            prefs.edit().putBoolean("theme_sound_enabled", true).apply()
            playThemeSound()
        }
        if (query.isEmpty()) {
            adapter.updateList(currentFolder.items)
        } else {
            val filtered = mutableListOf<Any>()
            val searchRoot = directIrPilotFolder ?: directIrLibraryRoot ?: allData
            findInFolder(searchRoot, query, filtered)
            adapter.updateList(filtered)
        }
    }
    private fun findInFolder(folder: IrFolder, query: String, results: MutableList<Any>) {
        folder.items.forEach { 
            if (it is Command) { 
                if (it.name.contains(query, ignoreCase = true)) results.add(it) 
            } else if (it is IrFolder) { 
                if (it.name.contains(query, ignoreCase = true)) results.add(it)
                findInFolder(it, query, results) 
            } 
        } 
    }

    private fun gatherCommandsRecursively(folder: IrFolder, list: MutableList<Command>) {
        folder.items.forEach {
            when (it) {
                is Command -> list.add(it)
                is IrFolder -> gatherCommandsRecursively(it, list)
            }
        }
    }

    private fun showFolderSmartMacroDialog(folder: IrFolder) {
        val allCommands = mutableListOf<Command>()
        gatherCommandsRecursively(folder, allCommands)

        if (allCommands.isEmpty()) {
            Toast.makeText(this, "Brak komend w tym folderze!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xFF141B25.toInt())
        }

        val tvTitle = TextView(this).apply {
            text = "⚡ Smart Macro: ${folder.name}"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dialogView.addView(tvTitle)

        val etFilter = EditText(this).apply {
            hint = "Filtruj słowo kluczowe [np. Power, Vol] (opcjonalnie)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8290A2.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        dialogView.addView(etFilter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })

        val tvStatus = TextView(this).apply {
            text = "Dostępnych komend: ${allCommands.size}"
            textSize = 14f
            setTextColor(0xFF43C59E.toInt())
            setPadding(0, dp(4), 0, dp(10))
        }
        dialogView.addView(tvStatus)

        val tvDelay = TextView(this).apply {
            text = "Odstęp (Tempo): 600 ms"
            textSize = 13f
            setTextColor(0xFF9EADBD.toInt())
        }
        dialogView.addView(tvDelay)

        val delaySlider = SeekBar(this).apply {
            max = 2000
            progress = 600
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvDelay.text = "Odstęp (Tempo): ${progress.coerceAtLeast(100)} ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        dialogView.addView(delaySlider, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        var currentIndex = 0
        var isPlaying = false
        var macroJob: kotlinx.coroutines.Job? = null
        var activeCommands = listOf<Command>()
        var currentActiveCmd: Command? = null

        val btnPlay = Button(this).apply {
            text = "▶ Start (Play)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
        }

        val btnPause = Button(this).apply {
            text = "⏸ Pauza"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
        }

        val btnPrev = Button(this).apply {
            text = "◄ Poprz"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (activeCommands.isEmpty()) {
                    val query = etFilter.text.toString().trim()
                    activeCommands = if (query.isEmpty()) allCommands else allCommands.filter { it.name.contains(query, true) }
                }
                if (activeCommands.isNotEmpty()) {
                    currentIndex = (currentIndex - 1).coerceAtLeast(0)
                    val cmd = activeCommands[currentIndex]
                    currentActiveCmd = cmd
                    val sourcePath = cmd.sourceRemotePath ?: IrCommandOrigin.findCommandPath(allData, cmd).orEmpty()
                    val remoteName = sourcePath.lastOrNull() ?: "Nieznany pilot"
                    tvStatus.text = "[%d / %s] %s\n📁 Pilot: %s (Kliknij, aby przejść)".format(currentIndex + 1, activeCommands.size, cmd.name, remoteName)
                    val pattern = cmd.getEffectivePattern()
                    if (cmd.frequency > 0 && !pattern.contentEquals(intArrayOf(100, 100))) {
                        transmitter.transmit(cmd.frequency, pattern)
                        addToRecent(cmd)
                    }
                }
            }
        }

        val btnNext = Button(this).apply {
            text = "Nst ►"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (activeCommands.isEmpty()) {
                    val query = etFilter.text.toString().trim()
                    activeCommands = if (query.isEmpty()) allCommands else allCommands.filter { it.name.contains(query, true) }
                }
                if (activeCommands.isNotEmpty()) {
                    currentIndex = (currentIndex + 1).coerceAtMost(activeCommands.size - 1)
                    val cmd = activeCommands[currentIndex]
                    currentActiveCmd = cmd
                    val sourcePath = cmd.sourceRemotePath ?: IrCommandOrigin.findCommandPath(allData, cmd).orEmpty()
                    val remoteName = sourcePath.lastOrNull() ?: "Nieznany pilot"
                    tvStatus.text = "[%d / %s] %s\n📁 Pilot: %s (Kliknij, aby przejść)".format(currentIndex + 1, activeCommands.size, cmd.name, remoteName)
                    val pattern = cmd.getEffectivePattern()
                    if (cmd.frequency > 0 && !pattern.contentEquals(intArrayOf(100, 100))) {
                        transmitter.transmit(cmd.frequency, pattern)
                        addToRecent(cmd)
                    }
                }
            }
        }

        val btnRewind = Button(this).apply {
            text = "⏪ -5"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                currentIndex = (currentIndex - 5).coerceAtLeast(0)
                tvStatus.text = "⏪ Cofnięto do pozycji [%d / %s]".format(currentIndex + 1, activeCommands.size.coerceAtLeast(allCommands.size))
            }
        }

        val btnForward = Button(this).apply {
            text = "⏩ +5"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                val maxSz = activeCommands.size.coerceAtLeast(allCommands.size)
                currentIndex = (currentIndex + 5).coerceAtMost(maxSz - 1)
                tvStatus.text = "⏩ Przewinięto do pozycji [%d / %s]".format(currentIndex + 1, maxSz)
            }
        }

        val controlRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlRow1.addView(btnPlay, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        controlRow1.addView(btnPause, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        dialogView.addView(controlRow1)

        val controlRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        controlRow2.addView(btnPrev, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
        controlRow2.addView(btnRewind, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        controlRow2.addView(btnForward, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        controlRow2.addView(btnNext, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) })

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("ZAMKNIJ") { _, _ ->
                macroJob?.cancel()
            }
            .create()

        tvStatus.paintFlags = tvStatus.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        tvStatus.isClickable = true
        tvStatus.isFocusable = true
        tvStatus.setOnClickListener {
            val cmd = currentActiveCmd ?: return@setOnClickListener
            val sourcePath = cmd.sourceRemotePath ?: IrCommandOrigin.findCommandPath(allData, cmd)
            val nonNullPath = sourcePath.orEmpty()
            if (nonNullPath.isNotEmpty()) {
                val targetFolder = resolveFolderPath(nonNullPath)
                if (targetFolder != null) {
                    dialog.dismiss()
                    macroJob?.cancel()
                    navigateToRemoteFolder(targetFolder)
                    Toast.makeText(this, "Przejście do pilota: ${nonNullPath.lastOrNull()}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Nie znaleziono folderu pilota", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Brak ścieżki źródłowej tego pilota", Toast.LENGTH_SHORT).show()
            }
        }

        fun runMacroLoop() {
            macroJob?.cancel()
            val query = etFilter.text.toString().trim()
            activeCommands = if (query.isEmpty()) {
                allCommands
            } else {
                allCommands.filter { it.name.contains(query, ignoreCase = true) }
            }

            if (activeCommands.isEmpty()) {
                Toast.makeText(this, "Brak komend pasujących do filtra '$query'!", Toast.LENGTH_SHORT).show()
                return
            }

            macroJob = lifecycleScope.launch {
                isPlaying = true
                btnPlay.text = "▶ Odtwarzanie..."
                while (isPlaying && currentIndex < activeCommands.size) {
                    val cmd = activeCommands[currentIndex]
                    currentActiveCmd = cmd
                    val sourcePath = cmd.sourceRemotePath ?: IrCommandOrigin.findCommandPath(allData, cmd).orEmpty()
                    val remoteName = sourcePath.lastOrNull() ?: "Nieznany pilot"
                    tvStatus.text = "[%d / %s] %s\n📁 Pilot: %s (Kliknij, aby przejść)".format(currentIndex + 1, activeCommands.size, cmd.name, remoteName)
                    
                    val pattern = cmd.getEffectivePattern()
                    if (cmd.frequency > 0 && !pattern.contentEquals(intArrayOf(100, 100))) {
                        transmitter.transmit(cmd.frequency, pattern)
                        addToRecent(cmd)
                    }

                    val delayTime = delaySlider.progress.toLong().coerceAtLeast(100L)
                    kotlinx.coroutines.delay(delayTime)
                    currentIndex++
                }
                if (currentIndex >= activeCommands.size) {
                    tvStatus.text = "✔ Zakończono test komend!"
                    currentIndex = 0
                }
                isPlaying = false
                btnPlay.text = "▶ Wznów (Play)"
            }
        }

        btnPlay.setOnClickListener {
            if (!isPlaying) runMacroLoop()
        }

        btnPause.setOnClickListener {
            isPlaying = false
            macroJob?.cancel()
            btnPlay.text = "▶ Wznów (Play)"
            tvStatus.text = "⏸ Zatrzymano na pozycji [%d / %s]: %s".format(currentIndex + 1, activeCommands.size, activeCommands.getOrNull(currentIndex)?.name ?: "")
        }

        btnRewind.setOnClickListener {
            currentIndex = (currentIndex - 5).coerceAtLeast(0)
            tvStatus.text = "⏪ Cofnięto do pozycji [%d / %s]".format(currentIndex + 1, activeCommands.size)
        }

        btnForward.setOnClickListener {
            currentIndex = (currentIndex + 5).coerceAtMost(activeCommands.size - 1)
            tvStatus.text = "⏩ Przewinięto do pozycji [%d / %s]".format(currentIndex + 1, activeCommands.size)
        }

        dialog.show()
    }
    private fun showMoveDialog(item: Any, pos: Int) {
        val folders = mutableListOf<IrFolder>(); findAllFolders(allData, folders)
        val names = folders.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Move").setItems(names) { _, w -> val target = folders[w]; if (target != currentFolder) { currentFolder.items.removeAt(pos); target.items.add(item); save { runOnUiThread { refreshList() } } } }.show()
    }

    private fun findAllFolders(root: IrFolder, list: MutableList<IrFolder>) { list.add(root); root.items.forEach { if (it is IrFolder) findAllFolders(it, list) } }
    private fun findParent(root: IrFolder, target: IrFolder): IrFolder? { for (item in root.items) { if (item is IrFolder) { if (item === target) return root; val f = findParent(item, target); if (f != null) return f } } ; return null }

    private fun startBridgeServer() {
        bridgeJob?.cancel()
        bridgeJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(8080)
                while (isActive) {
                    val client = server.accept()
                    val reader = client.getInputStream().bufferedReader()
                    val line = reader.readLine() // Simple one-line capture
                    if (line != null && line.contains("{")) {
                        withContext(Dispatchers.Main) {
                            showLiveCaptureDialog(line)
                        }
                    }
                    client.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("BruceIR", "Bridge Server Error: ${e.message}")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
            "Unknown"
        } catch (e: Exception) { "Error" }
    }

    private fun performBruceHandshake(onResult: (String, Boolean) -> Unit = { _, _ -> }) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        val user = prefs.getString("bruce_user", "admin") ?: "admin"
        val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"

        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) {
                val normalizedUrl = baseUrl.trim().let {
                    when {
                        it.startsWith("https://", ignoreCase = true) -> it
                        it.startsWith("http://", ignoreCase = true) -> it
                        else -> "http://$it"
                    }.trimEnd('/')
                }
                val connection = try {
                    (java.net.URL("$normalizedUrl/systeminfo").openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        requestMethod = "GET"
                        if (user.isNotEmpty() && pass.isNotEmpty()) {
                            val credentials = "$user:$pass".toByteArray()
                            val auth = android.util.Base64.encodeToString(credentials, android.util.Base64.NO_WRAP)
                            setRequestProperty("Authorization", "Basic $auth")
                        }
                    }
                } catch (_: Exception) {
                    return@withContext -1
                }
                try {
                    connection.responseCode
                } catch (_: Exception) {
                    -1
                } finally {
                    connection.disconnect()
                }
            }
            val successful = code in 200..299
            val message = when {
                successful -> getString(R.string.home_connection_online, code)
                code in 100..599 -> getString(R.string.home_connection_response, code)
                else -> getString(R.string.home_connection_failed)
            }
            onResult(message, successful)
        }
    }

    private fun showInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        
        // IP Display
        try {
            view.findViewById<TextView>(R.id.tvIrStatus).text = "Phone IP: ${getLocalIpAddress()}"
            view.findViewById<TextView>(R.id.tvUsbStatus).text = "Bridge Port: 8080"
        } catch (e: Exception) {}

        // Hidden trigger for LuxActivity
        view.findViewById<View>(R.id.ivInfoLogo).setOnClickListener {
            startActivity(Intent(this, LuxActivity::class.java))
        }

        view.findViewById<Button>(R.id.btnChangeLang).setOnClickListener { val cur = AppCompatDelegate.getApplicationLocales(); val next = if (cur.isEmpty || cur.get(0)?.language == "en") "pl" else "en"; AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next)) }
        
        val etUrl = view.findViewById<EditText>(R.id.etBruceUrl)
        etUrl.setText(prefs.getString("bruce_url", "http://bruce.local"))
        val etUser = view.findViewById<EditText>(R.id.etBruceUser)
        etUser.setText(prefs.getString("bruce_user", "admin"))
        val etPassword = view.findViewById<EditText>(R.id.etBrucePassword)
        etPassword.setText(prefs.getString("bruce_pass", "bruce"))
        
        view.findViewById<Button>(R.id.btnTestConnection).setOnClickListener {
            val url = etUrl.text.toString().ifEmpty { "http://bruce.local" }
            val username = etUser.text.toString()
            val password = etPassword.text.toString()
            prefs.edit()
                .putString("bruce_url", url)
                .putString("bruce_user", username)
                .putString("bruce_pass", password)
                .apply()
            val status = view.findViewById<TextView>(R.id.tvBruceStatus)
            status.setText(R.string.home_connection_checking)
            performBruceHandshake { result, successful ->
                status.text = result
                status.setTextColor(getColor(if (successful) R.color.accent_green else R.color.accent_red))
                lastBruceConnectionMessage = result
                lastBruceConnectionSuccessful = successful
                updateHomeStatus()
            }
        }

        val swInstant = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swInstantBoot)
        swInstant.isChecked = prefs.getBoolean("instant_boot", false)
        swInstant.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("instant_boot", isChecked).apply() }
        
        val swSound = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swClickSound)
        swSound.isChecked = prefs.getBoolean("click_sound", true)
        swSound.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("click_sound", isChecked).apply()
            adapter.setSoundEnabled(isChecked)
        }
        
        val swHaptic = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swHaptic)
        swHaptic.isChecked = prefs.getBoolean("haptic_enabled", true)
        swHaptic.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("haptic_enabled", isChecked).apply()
        }

        val swThemeSound = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swThemeSound)
        swThemeSound.isChecked = prefs.getBoolean("theme_sound_enabled", true)
        swThemeSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("theme_sound_enabled", isChecked).apply()
            if (isChecked) playThemeSound() else stopThemeSound()
        }

        val swWebHub = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swWebHub)
        swWebHub.isChecked = prefs.getBoolean("web_hub_enabled", false)
        swWebHub.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("web_hub_enabled", isChecked).apply()
            if (isChecked) {
                AppWebFileServerController.start(this)
                Toast.makeText(this, "Serwer Web File Hub aktywny (http://bruceir.local:8080)", Toast.LENGTH_LONG).show()
            } else {
                AppWebFileServerController.stop()
                Toast.makeText(this, "Serwer Web File Hub zatrzymany", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.home_connection_test_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun showMacroListDialog() {
        val macros = macroManager.getAllMacros()
        if (macros.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Macros")
                .setMessage("No macros found. Long-press any button to add it to a macro.")
                .setPositiveButton("CREATE NEW") { _, _ -> showCreateEmptyMacro() }
                .setNegativeButton("CANCEL", null).show()
            return
        }

        val names = macros.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Macro")
            .setItems(names) { _, which -> showMacroActions(macros[which]) }
            .setPositiveButton("NEW MACRO") { _, _ -> showCreateEmptyMacro() }
            .show()
    }

    private fun showCreateEmptyMacro() {
        val input = EditText(this).apply { hint = "Macro Name" }
        AlertDialog.Builder(this)
            .setTitle("Create Macro")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().uppercase()
                if (name.isNotEmpty()) {
                    macroManager.updateMacro(MacroSet(name))
                    showMacroListDialog()
                }
            }.show()
    }

    private fun showMacroActions(macro: MacroSet) {
        val ops = arrayOf("RUN MACRO", "EDIT COMMANDS", "SETTINGS (Delay/Loop)", "DELETE MACRO")
        AlertDialog.Builder(this)
            .setTitle("Macro: ${macro.name}")
            .setItems(ops) { _, which ->
                when (which) {
                    0 -> runMacro(macro)
                    1 -> showMacroCommandEditor(macro)
                    2 -> showMacroSettingsDialog(macro)
                    3 -> confirmAction(R.string.confirm_delete_msg) {
                        val all = macroManager.getAllMacros()
                        all.removeAll { it.name == macro.name }
                        macroManager.saveAllMacros(all)
                        showMacroListDialog()
                    }
                }
            }.show()
    }

    private fun runMacro(macro: MacroSet) {
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Running ${macro.name}")
            setMessage("Starting...")
            setCancelable(false)
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "STOP") { _, _ -> macroManager.stop() }
            show()
        }
        macroManager.start(macro, 
            { cur, total, name ->
                val command = macro.commands.getOrNull(cur - 1)
                val sourcePath = command?.sourceRemotePath
                    ?: command?.let { IrCommandOrigin.findCommandPath(allData, it) }
                val source = sourcePath?.takeIf { it.isNotEmpty() }?.joinToString(" › ")
                progress.setMessage(
                    if (source == null) "Sending $cur/$total: $name"
                    else "Sending $cur/$total: $name\nPilot: $source"
                )
            },
            { },
            { progress.dismiss(); Toast.makeText(this, "Macro Finished", Toast.LENGTH_SHORT).show() }
        )
    }

    private fun showMacroCommandEditor(macro: MacroSet) {
        if (macro.commands.isEmpty()) {
            Toast.makeText(this, "Macro is empty. Long-press buttons to add.", Toast.LENGTH_LONG).show()
            return
        }
        val names = macro.commands.mapIndexed { index, cmd ->
            val sourcePath = cmd.sourceRemotePath ?: IrCommandOrigin.findCommandPath(allData, cmd)
            buildString {
                append("${index + 1}. ${cmd.name}")
                if (!sourcePath.isNullOrEmpty()) {
                    append("\n")
                    append(getString(R.string.macro_command_source, sourcePath.joinToString(" › ")))
                }
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Edit Commands: ${macro.name}")
            .setItems(names) { _, which ->
                val ops = mutableListOf("Open remote", "Move UP", "Move DOWN", "Remove")
                AlertDialog.Builder(this)
                    .setTitle(macro.commands[which].name)
                    .setItems(ops.toTypedArray()) { _, op ->
                        when (op) {
                            0 -> {
                                val command = macro.commands[which]
                                val sourcePath = command.sourceRemotePath
                                    ?: IrCommandOrigin.findCommandPath(allData, command)
                                if (sourcePath.isNullOrEmpty()) {
                                    Toast.makeText(this, "Source remote could not be found.", Toast.LENGTH_SHORT).show()
                                } else {
                                    startActivity(Intent(this, MainActivity::class.java).apply {
                                        putExtra("ACTION", "OPEN_IR_REMOTE_PATH")
                                        putStringArrayListExtra("IR_REMOTE_PATH", ArrayList(sourcePath))
                                    })
                                }
                            }
                            1 -> if (which > 0) {
                                java.util.Collections.swap(macro.commands, which, which - 1)
                                macroManager.updateMacro(macro)
                                showMacroCommandEditor(macro)
                            }
                            2 -> if (which < macro.commands.size - 1) {
                                java.util.Collections.swap(macro.commands, which, which + 1)
                                macroManager.updateMacro(macro)
                                showMacroCommandEditor(macro)
                            }
                            3 -> {
                                macro.commands.removeAt(which)
                                macroManager.updateMacro(macro)
                                showMacroCommandEditor(macro)
                            }
                        }
                    }.show()
            }
            .setPositiveButton("BACK", { _, _ -> showMacroActions(macro) })
            .show()
    }

    private fun showMacroSettingsDialog(macro: MacroSet) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        val delayIn = EditText(this).apply { 
            hint = "Delay in ms (e.g. 600)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(macro.delayMs.toString())
        }
        val loopCheck = CheckBox(this).apply { 
            text = "Loop Macro"
            isChecked = macro.isLooping
            setPadding(0, 20, 0, 20)
        }
        lay.addView(TextView(this).apply { text = "Delay between signals (ms):" })
        lay.addView(delayIn)
        lay.addView(loopCheck)

        AlertDialog.Builder(this)
            .setTitle("Macro Settings")
            .setView(lay)
            .setPositiveButton("SAVE") { _, _ ->
                macro.delayMs = delayIn.text.toString().toLongOrNull() ?: 600L
                macro.isLooping = loopCheck.isChecked
                macroManager.updateMacro(macro)
                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showFlipperImportDialog() {
        val options = arrayOf(
            "🐬 Wbudowana baza Flipper IRDB (wczytaj/wczytaj ponownie)",
            "📦 Plik bazy Flipper (.json / .json.gz)",
            "📁 Plik pilotów IR (.ir / .json)"
        )
        AlertDialog.Builder(this)
            .setTitle("Import Kodów IR / Flipper DB")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadBuiltInFlipperDb()
                    1 -> {
                        pendingFlipperDbImport = true
                        pickFile.launch("*/*")
                    }
                    2 -> {
                        pendingFlipperDbImport = false
                        pickFile.launch("*/*")
                    }
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun loadBuiltInFlipperDb() {
        val pilotRoot = directIrPilotFolder ?: directIrLibraryRoot ?: allData
        val flipperFolder = BruceFlipperCache.locateOrCreate(pilotRoot)
        openManagedFlipperFolder(flipperFolder)
    }

    /**
     * Otwiera zarządzany folder bazy Flipper. Placeholder (albo komunikat błędu)
     * jest zamieniany na zawartość bazy z pokazem postępu; wczytana baza jest
     * cache'owana w pamięci procesu, więc kolejne otwarcia są natychmiastowe.
     */
    private fun openManagedFlipperFolder(folder: IrFolder, forceReload: Boolean = false) {
        if (!forceReload && !BruceFlipperCache.isPlaceholder(folder)) {
            navigateIntoFolder(folder)
            return
        }
        if (forceReload) BruceFlipperCache.clearCache()

        val progressLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        progressLayout.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        progressLayout.addView(TextView(this).apply {
            text = "Dekompresja i parsowanie bazy Flipper IRDB (kilkadziesiąt MB)…"
            setTextColor(0xFF9EADBD.toInt())
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Wczytywanie bazy Flipper IRDB")
            .setView(progressLayout)
            .setNegativeButton("ANULUJ", null)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val loadJob = lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    BruceFlipperCache.loadInto(this@MainActivity, folder)
                }
                if (progressDialog.isShowing) progressDialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "Wczytano bazę Flipper IRDB: $count kategorii",
                    Toast.LENGTH_SHORT
                ).show()
                navigateIntoFolder(folder)
                save { runOnUiThread { refreshList() } }
            } catch (e: Exception) {
                if (progressDialog.isShowing) progressDialog.dismiss()
                showFlipperLoadError(folder, e)
            }
        }
        progressDialog.setOnDismissListener { loadJob.cancel() }
    }

    private fun navigateIntoFolder(folder: IrFolder) {
        binding.etSearch.setText("")
        binding.etSearch.visibility = View.GONE
        if (currentFolder !== folder) {
            folderNavigationStack.push(currentFolder)
            currentFolder = folder
        }
        refreshList()
    }

    private fun showFlipperLoadError(folder: IrFolder, error: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Nie udało się wczytać bazy Flipper")
            .setMessage(
                "${error.message ?: "nieznany błąd"}\n\n" +
                    "Możesz spróbować ponownie albo wskazać własny plik bazy " +
                    "(flipper_db.json / flipper_db.json.gz / .ir)."
            )
            .setPositiveButton("SPRÓBUJ PONOWNIE") { _, _ ->
                openManagedFlipperFolder(folder, forceReload = true)
            }
            .setNeutralButton("WSKAŻ PLIK") { _, _ ->
                pendingFlipperDbImport = true
                pickFile.launch("*/*")
            }
            .setNegativeButton("ANULUJ", null)
            .show()
    }

    private fun showImportUrlDialog() {
        val input = EditText(this).apply { setText("https://raw.githubusercontent.com/MrMTi1/Bruce-IR/master/Ir_codes.json") }
        AlertDialog.Builder(this).setTitle("Import URL").setView(input).setPositiveButton("OK") { _, _ -> fetchAndImportIr(input.text.toString()) }.show()
    }

    private fun normalizeGithubUrl(url: String): String {
        var u = url.trim()
        if (u.contains("github.com") && u.contains("/blob/")) {
            u = u.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
        }
        return u
    }

    private fun fetchAndImportIr(url: String) {
        val targetUrl = normalizeGithubUrl(url)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Pobieranie z URL...")
            .setMessage("Łączenie z $targetUrl")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    val conn = URL(targetUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    if (conn.responseCode !in 200..299) {
                        throw Exception("HTTP error code: ${conn.responseCode}")
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    val suggestedName = runCatching { URL(targetUrl).path.substringAfterLast('/') }.getOrNull()
                    processIrContent(content, suggestedName.orEmpty())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Import URL failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun processIrContent(content: String, suggestedFolderName: String) {
        val destination = directIrPilotFolder ?: allData
        val baseName = suggestedFolderName
            .substringAfterLast('/')
            .replace(Regex("(?i)\\.(ir|json)$"), "")
            .trim()
            .ifEmpty { getString(R.string.ir_imported_remote) }

        fun addImportedFolder(folder: IrFolder, preferredName: String? = null) {
            var name = preferredName?.takeIf { it.isNotBlank() && it != "ROOT" && it != "Moje Piloty" } ?: baseName
            var suffix = 2
            while (destination.items.filterIsInstance<IrFolder>().any { it.name.equals(name, ignoreCase = true) }) {
                name = "$baseName ($suffix)"
                suffix++
            }
            folder.name = name
            if (folder.name.equals("ROOT", ignoreCase = true) || folder.name == "Moje Piloty") {
                folder.name = baseName
            }
            destination.items.add(folder)
            save { 
                runOnUiThread { 
                    refreshList() 
                    Toast.makeText(
                        this@MainActivity,
                        "Zaimportowano pomyślnie folder: ${folder.name} (${folder.items.size} elementów)",
                        Toast.LENGTH_LONG
                    ).show()
                } 
            }
        }

        // 1. Spróbuj jako JSON (Backup lub lista)
        val jsonData = BruceUtils.parseJsonContent(content)
        if (jsonData != null) {
            if (jsonData is IrFolder) {
                val preferred = jsonData.name
                    .takeIf { it.isNotBlank() && it != "ROOT" && it != "Moje Piloty" }
                addImportedFolder(jsonData, preferred)
                return
            } else if (jsonData is List<*>) {
                val cmds = jsonData.filterIsInstance<Command>()
                if (cmds.isNotEmpty()) {
                    addImportedFolder(IrFolder(baseName, cmds.toMutableList<Any>()))
                    return
                }
            }
        }

        // 2. Jeśli nie JSON, spróbuj jako tekst .ir (Flipper)
        val cmds = BruceUtils.parseIrContent(content)
        if (cmds.isNotEmpty()) {
            addImportedFolder(IrFolder(baseName, cmds.toMutableList<Any>()))
        } else {
            Toast.makeText(this, "Nie znaleziono poprawnych komend IR w pobranym pliku", Toast.LENGTH_LONG).show()
        }
    }

    private fun performExport(uri: Uri) { try { contentResolver.openOutputStream(uri)?.use { it.write(gson.toJson(allData).toByteArray()) } } catch (e: Exception) {} }
    private fun performFullImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                allData = BruceUtils.streamParseJson(input.bufferedReader())
                ensureSystemHubFolders()
                directIrLibraryRoot = allData.items.firstOrNull {
                    it is IrFolder && it.name.contains("PILOTY", ignoreCase = true)
                } as? IrFolder
                currentFolder = directIrLibraryRoot ?: allData
                save { runOnUiThread { refreshList() } }
            }
        } catch (_: Exception) {}
    }
    private fun importBruceFile(uri: Uri) {
        val wantsFlipperDb = pendingFlipperDbImport
        pendingFlipperDbImport = false
        try {
            val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                .orEmpty()
            val maxImportBytes = 96L * 1024L * 1024L
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > maxImportBytes) {
                    Toast.makeText(
                        this,
                        "Plik jest za duży (${descriptor.length / (1024 * 1024)} MB). Limit importu to 96 MB.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }
            val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Could not open selected file")
            val isGzip = rawBytes.size > 2 &&
                rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()
            val isZip = (rawBytes.size > 2 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) ||
                displayName.endsWith(".zip", ignoreCase = true)

            if (isZip) {
                importZipFile(rawBytes, displayName)
                return
            }

            val looksLikeFlipperDb = wantsFlipperDb ||
                displayName.contains("flipper", ignoreCase = true) ||
                displayName.endsWith(".gz", ignoreCase = true) ||
                isGzip

            if (looksLikeFlipperDb) {
                importFlipperDbBytes(rawBytes)
                return
            }
            val text = try {
                String(rawBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                String(rawBytes, Charsets.ISO_8859_1)
            }
            processIrContent(text, displayName)
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importZipFile(bytes: ByteArray, zipName: String) {
        val baseFolderName = zipName.substringBeforeLast('.').ifEmpty { "Imported ZIP" }
        val rootFolder = IrFolder(baseFolderName)
        try {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".ir", ignoreCase = true)) {
                        val text = zis.bufferedReader(Charsets.UTF_8).readText()
                        val cmds = BruceUtils.parseIrContent(text)
                        if (cmds.isNotEmpty()) {
                            val remoteName = entry.name.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Remote" }
                            rootFolder.items.add(IrFolder(remoteName, cmds.toMutableList<Any>()))
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (rootFolder.items.isNotEmpty()) {
                val destination = directIrPilotFolder ?: allData
                destination.items.add(rootFolder)
                save { runOnUiThread { refreshList() } }
                Toast.makeText(this, "Zaimportowano ZIP: ${rootFolder.items.size} pilotów", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Brak plików .ir w archiwum ZIP", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd importu ZIP: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Wczytuje bazę Flipper wskazaną przez użytkownika i podstawia ją w zarządzanym folderze. */
    private fun importFlipperDbBytes(bytes: ByteArray) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Import bazy Flipper")
            .setMessage("Parsowanie pliku bazy…")
            .setCancelable(false)
            .create()
        progressDialog.show()
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    BruceFlipperCache.loadFromBytes(this@MainActivity, bytes)
                }
                val pilotRoot = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                val target = BruceFlipperCache.locateOrCreate(pilotRoot)
                withContext(Dispatchers.Main) {
                    target.items.clear()
                    target.items.addAll(loaded.items)
                    target.name = BruceFlipperCache.FOLDER_NAME
                    directIrLibraryMode = true
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    navigateIntoFolder(target)
                    Toast.makeText(
                        this@MainActivity,
                        "Zaimportowano bazę Flipper: ${loaded.items.size} kategorii",
                        Toast.LENGTH_LONG
                    ).show()
                }
                save { runOnUiThread { refreshList() } }
            } catch (e: Exception) {
                if (progressDialog.isShowing) progressDialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "Błąd importu bazy Flipper: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    private fun openPendingRemoteFolder() {
        val path = pendingRemotePath ?: return
        pendingRemotePath = null
        val target = resolveRemoteFolder(path)
        if (target == null) {
            Toast.makeText(this, "Nie udało się odnaleźć pilota z makra.", Toast.LENGTH_LONG).show()
            return
        }
        val route = findFolderRoute(allData, target).orEmpty()
        folderNavigationStack.clear()
        route.dropLast(1).forEach(folderNavigationStack::push)
        directIrLibraryMode = true
        currentFolder = target
        refreshList()
    }

    private fun resolveRemoteFolder(path: List<String>): IrFolder? {
        if (path.isEmpty()) return null
        resolveFolderPath(path)?.let { return it }
        val normalizedPath = path.map(::normalizeRemotePathSegment).filter(String::isNotEmpty)
        val targetName = normalizedPath.lastOrNull() ?: return null
        val candidates = mutableListOf<Pair<IrFolder, List<String>>>()
        fun collect(folder: IrFolder) {
            folder.items.filterIsInstance<IrFolder>().forEach { child ->
                candidates.add(child to folderPathFromRoot(child).map(::normalizeRemotePathSegment))
                collect(child)
            }
        }
        collect(allData)

        var bestScore = 0
        var best: IrFolder? = null
        var ambiguous = false
        candidates.forEach { (folder, candidatePath) ->
            if (candidatePath.lastOrNull() != targetName) return@forEach
            var score = 0
            while (
                score < normalizedPath.size &&
                score < candidatePath.size &&
                normalizedPath[normalizedPath.lastIndex - score] ==
                candidatePath[candidatePath.lastIndex - score]
            ) {
                score++
            }
            if (score > bestScore) {
                bestScore = score
                best = folder
                ambiguous = false
            } else if (score == bestScore && score > 0 && best !== folder) {
                ambiguous = true
            }
        }
        return best?.takeUnless { ambiguous }
    }

    private fun normalizeRemotePathSegment(segment: String): String =
        segment.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    private fun findFolderRoute(root: IrFolder, target: IrFolder): List<IrFolder>? {
        root.items.filterIsInstance<IrFolder>().forEach { child ->
            if (child === target) return listOf(child)
            findFolderRoute(child, target)?.let { return listOf(child) + it }
        }
        return null
    }
    
    private fun handleSystemCommandClick(cmd: Command) {
        if (currentFolder === directIrSettingsFolder) {
            when (cmd.name) {
                getString(R.string.ir_settings_add_remote) -> {
                    currentFolder = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                    showAddFolder()
                }
                getString(R.string.ir_settings_add_command) -> chooseIrFolder {
                    currentFolder = it
                    showAddManual()
                }
                getString(R.string.ir_settings_import_file) -> {
                    currentFolder = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                    pickFile.launch("*/*")
                }
                getString(R.string.ir_settings_import_url) -> {
                    currentFolder = directIrPilotFolder ?: directIrLibraryRoot ?: allData
                    showImportUrlDialog()
                }
                getString(R.string.ir_settings_export_backup) -> exportLauncher.launch("BruceIR_Backup.json")
                getString(R.string.ir_settings_restore_backup) -> importFullDbLauncher.launch("application/json")
                getString(R.string.ir_settings_options) -> showIrLibrarySettings()
                getString(R.string.ir_settings_downloaded) -> showDownloadedRemoteManager()
                getString(R.string.ir_settings_tv_b_gone) -> addTvBGoneRemote()
            }
            return
        }
        val name = cmd.name
        when {
            name.contains("WPA Cracker") -> showWpaCrackerDialog()
            name.contains("Konwerter PCAP") -> startActivity(Intent(this, PcapConverterActivity::class.java))
            name.contains("Skaner Celów") || name.contains("Live Target") -> requestPhoneWifiTargets()
            name.contains("Serwer Web") -> toggleAppWebFileServer()
            name.contains("Złamanych Haseł") -> {
                val potfile = File(filesDir, "cracked_passwords.txt")
                val content = if (potfile.exists()) potfile.readText() else "Brak zapisanych złamanych haseł."
                AlertDialog.Builder(this)
                    .setTitle("🔑 Złamane Hasła (Potfile)")
                    .setMessage(content)
                    .setPositiveButton("OK", null)
                    .show()
            }
            name.contains("Remote Console") || name.contains("Mirror BLE") -> startActivity(Intent(this, RemoteConsoleActivity::class.java))
            name.contains("Bruce Web UI") -> startActivity(Intent(this, BruceWebActivity::class.java))
            name.contains("Bluetooth Command") -> bleConsoleLauncher.launch(Intent(this, BruceBleConsoleActivity::class.java))
            name.contains("Moduły Bruce") -> startActivity(Intent(this, RemoteConsoleActivity::class.java).apply { putExtra("show_modules", true) })
            name.contains("Live Capture") -> chooseCaptureFolder(IrCaptureTransport.BLE)
            name.contains("Nadajnik IR") -> showTransmitterModeDialog()
            name.contains("Dźwięk Motywu") || name.contains("Informacje") || name.contains("Ustawienia") -> showInfoDialog()
            name.contains("Zmień Język") -> {
                val cur = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                val next = if (cur.isEmpty || cur.get(0)?.language == "en") "pl" else "en"
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(next))
            }
            else -> Toast.makeText(this, "Uruchamianie: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSmartMacroSearchDialog() {
        val libraryRoot = directIrPilotFolder ?: directIrLibraryRoot ?: allData
        val libraryPrefix = IrCommandOrigin.findFolderPath(allData, libraryRoot).orEmpty()
        val commandIndex = IrCommandOrigin.searchCommands(libraryRoot).map { match ->
            match.copy(remotePath = libraryPrefix + match.remotePath)
        }
        val selectedKeys = linkedSetOf<String>()
        var currentMatches = emptyList<IrCommandOrigin.Match>()
        val input = EditText(this).apply {
            hint = getString(R.string.smart_macro_search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val summary = TextView(this).apply {
            setTextColor(0xFF9EADBD.toInt())
            textSize = 12f
            text = getString(R.string.smart_macro_search_hint)
        }
        val resultsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(resultsView)
            layoutParams = LinearLayout.LayoutParams(-1, dp(320))
        }
        val selectAll = com.google.android.material.button.MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(R.string.macro_workbench_select_all)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(summary, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            })
            addView(selectAll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(scroll)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.workbench_tool_smart_macro)
            .setView(panel)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.smart_macro_create, null)
            .setNeutralButton(R.string.smart_macro_send_now, null)
            .create()

        fun key(match: IrCommandOrigin.Match): String =
            "${match.remotePath.joinToString("/")}|${match.command.name}|${match.command.frequency}|${match.command.pattern.joinToString(",")}"

        fun refreshSelectionButton() {
            val visibleMatches = currentMatches.take(MAX_SMART_MACRO_RESULTS)
            val allVisibleSelected = visibleMatches.isNotEmpty() &&
                visibleMatches.all { key(it) in selectedKeys }
            selectAll.setText(
                if (allVisibleSelected) R.string.macro_workbench_deselect_all
                else R.string.macro_workbench_select_all
            )
            selectAll.isEnabled = visibleMatches.isNotEmpty()
        }

        fun updateResults(query: String) {
            val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            currentMatches = if (terms.isEmpty()) emptyList() else commandIndex.filter { match ->
                val searchable = "${match.command.name} ${match.remotePath.joinToString(" ")}"
                terms.all { searchable.contains(it, ignoreCase = true) }
            }
            resultsView.removeAllViews()
            summary.text = if (terms.isEmpty()) {
                getString(R.string.smart_macro_search_hint)
            } else {
                getString(
                    R.string.macro_workbench_search_count,
                    currentMatches.size,
                    minOf(currentMatches.size, MAX_SMART_MACRO_RESULTS)
                )
            }
            currentMatches.take(MAX_SMART_MACRO_RESULTS).forEach { match ->
                val checkbox = CheckBox(this).apply {
                    text = "${match.command.name}\n${match.remotePath.joinToString(" › ")}"
                    isChecked = key(match) in selectedKeys
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedKeys.add(key(match)) else selectedKeys.remove(key(match))
                        refreshSelectionButton()
                    }
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
                }
                resultsView.addView(checkbox)
            }
            refreshSelectionButton()
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateResults(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        selectAll.setOnClickListener {
            val visibleMatches = currentMatches.take(MAX_SMART_MACRO_RESULTS)
            val allVisibleSelected = visibleMatches.isNotEmpty() &&
                visibleMatches.all { key(it) in selectedKeys }
            visibleMatches.forEach { match ->
                if (allVisibleSelected) selectedKeys.remove(key(match)) else selectedKeys.add(key(match))
            }
            updateResults(input.text.toString())
        }

        dialog.setOnShowListener {
            fun getSelectedMatches(): List<IrCommandOrigin.Match> {
                val selected = currentMatches.filter { key(it) in selectedKeys }
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.macro_workbench_search_select, Toast.LENGTH_SHORT).show()
                }
                return selected
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = getSelectedMatches()
                if (selected.isNotEmpty()) showSmartMacroNameDialog(selected, input.text.toString(), dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selected = getSelectedMatches()
                if (selected.isNotEmpty()) {
                    dialog.dismiss()
                    playSmartMacroNow(selected.map { it.command.copy().apply { sourceRemotePath = it.remotePath } })
                }
            }
        }
        dialog.show()
    }

    private fun showSmartMacroNameDialog(
        matches: List<IrCommandOrigin.Match>,
        query: String,
        searchDialog: AlertDialog
    ) {
        val suggestedName = query.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), "_")
            .uppercase()
            .take(40)
            .ifBlank { "SMART_MACRO" }
        val nameInput = EditText(this).apply {
            setText(suggestedName)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_workbench_name_hint)
            .setView(nameInput)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.smart_macro_create) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.macro_workbench_name_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val macro = MacroSet(name).apply {
                    commands.addAll(matches.map { match ->
                        match.command.copy().apply { sourceRemotePath = match.remotePath }
                    })
                }
                macroManager.updateMacro(macro)
                searchDialog.dismiss()
                Toast.makeText(
                    this,
                    getString(R.string.macro_workbench_created_from_search, macro.commands.size),
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun playSmartMacroNow(commands: List<Command>) {
        if (commands.isEmpty()) return
        var nextCommand = 0
        var displayedCommand = 0
        var paused = false
        var stopped = false
        var sending = false
        var finished = false
        var playbackJob: Job? = null

        val message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val transportControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pauseButton = com.google.android.material.button.MaterialButton(this)
        val backOneButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val backFiveButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val nextButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val openRemoteButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)

        fun updateMessage(prefix: String? = null) {
            val command = commands.getOrNull(displayedCommand) ?: return
            val sourcePath = command.sourceRemotePath
            val source = sourcePath?.joinToString(" › ").orEmpty()
            message.text = buildString {
                if (prefix != null) append("$prefix\n")
                append(getString(R.string.smart_macro_playback_position, displayedCommand + 1, commands.size))
                append("\n${command.name}")
                if (source.isNotBlank()) append("\n${getString(R.string.macro_command_source, source)}")
            }
            openRemoteButton.isEnabled = !sourcePath.isNullOrEmpty()
        }

        fun updateControls() {
            pauseButton.setText(
                if (paused) R.string.smart_macro_resume else R.string.smart_macro_pause
            )
            pauseButton.isEnabled = !finished
            backOneButton.isEnabled = !sending && nextCommand > 0 && !finished
            backFiveButton.isEnabled = !sending && nextCommand > 0 && !finished
            nextButton.isEnabled = !sending && nextCommand < commands.size && !finished
            openRemoteButton.isEnabled = commands.getOrNull(displayedCommand)?.sourceRemotePath?.isNotEmpty() == true
        }

        pauseButton.setOnClickListener {
            if (!finished) {
                paused = !paused
                updateMessage(if (paused) getString(R.string.smart_macro_paused) else getString(R.string.smart_macro_resumed))
                updateControls()
            }
        }
        fun rewind(count: Int) {
            if (sending || finished) return
            paused = true
            nextCommand = (nextCommand - count).coerceAtLeast(0)
            displayedCommand = nextCommand.coerceAtMost(commands.lastIndex)
            updateMessage(getString(R.string.smart_macro_paused))
            updateControls()
        }
        backOneButton.setText(R.string.smart_macro_back_one)
        backOneButton.setOnClickListener { rewind(1) }
        backFiveButton.setText(R.string.smart_macro_back_five)
        backFiveButton.setOnClickListener { rewind(5) }
        nextButton.setText(R.string.smart_macro_skip)
        nextButton.setOnClickListener {
            if (!sending && !finished) {
                nextCommand = (nextCommand + 1).coerceAtMost(commands.size)
                displayedCommand = (nextCommand - 1).coerceAtLeast(0).coerceAtMost(commands.lastIndex)
                updateMessage(getString(R.string.smart_macro_skipped))
                updateControls()
            }
        }
        openRemoteButton.setText(R.string.macro_workbench_open_remote)
        openRemoteButton.setOnClickListener {
            val path = commands.getOrNull(displayedCommand)?.sourceRemotePath
            if (!path.isNullOrEmpty()) {
                paused = true
                updateControls()
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("ACTION", "OPEN_IR_REMOTE_PATH")
                    putStringArrayListExtra("IR_REMOTE_PATH", ArrayList(path))
                })
            }
        }
        listOf(backFiveButton, backOneButton, pauseButton, nextButton).forEach { button ->
            transportControls.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(4)
            })
        }
        controls.addView(transportControls)
        controls.addView(openRemoteButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(6)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.workbench_tool_smart_macro)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(message)
                addView(controls)
            })
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        dialog.setOnDismissListener {
            stopped = true
            playbackJob?.cancel()
        }
        dialog.show()
        updateMessage(getString(R.string.smart_macro_sending))
        updateControls()
        playbackJob = lifecycleScope.launch {
            try {
                while (!stopped && nextCommand < commands.size) {
                    if (paused) {
                        delay(100)
                        continue
                    }
                    val index = nextCommand
                    val command = commands[index]
                    displayedCommand = index
                    sending = true
                    updateMessage(getString(R.string.smart_macro_sending))
                    updateControls()
                    withContext(Dispatchers.IO) {
                        transmitter.transmit(command.frequency, command.pattern)
                    }
                    sending = false
                    nextCommand = index + 1
                    updateControls()
                    if (nextCommand < commands.size) delay(300)
                }
                if (!stopped) {
                    finished = true
                    paused = true
                    updateMessage(getString(R.string.smart_macro_finished))
                    updateControls()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sending = false
                finished = true
                updateMessage(getString(R.string.smart_macro_failed, error.message ?: ""))
                updateControls()
            }
        }
    }

    private fun load() {
        allData = BruceUtils.loadAllData(this)
        ensureSystemHubFolders()
    }

    private fun ensureSystemHubFolders() {
        allData.isListView = true

        val irRemotesFolder = allData.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name.equals("📺 PILOTY & KODY IR", ignoreCase = true) ||
                it.name.contains("PILOTY & KODY IR", ignoreCase = true)
        } ?: allData.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name.filter(Char::isLetterOrDigit).equals("PILOTY", ignoreCase = true) ||
                it.name.filter(Char::isLetterOrDigit).equals("PILOTYIKODYIR", ignoreCase = true)
        } ?: IrFolder("📺 PILOTY & KODY IR")
        irRemotesFolder.name = "📺 PILOTY & KODY IR"
        irRemotesFolder.colorHex = "#4DA3FF"
        irRemotesFolder.iconName = "📺"
        irRemotesFolder.isListView = false

        val pilotFolder = irRemotesFolder.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name.contains("WYBIERZ PILOTA", ignoreCase = true) ||
                it.name.equals("📺 PILOTY", ignoreCase = true)
        } ?: IrFolder("📺 PILOTY")
        pilotFolder.name = "📺 PILOTY"
        pilotFolder.colorHex = "#4DA3FF"
        pilotFolder.iconName = "📺"
        pilotFolder.isListView = false

        val settingsFolder = irRemotesFolder.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name.contains("USTAWIENIA PODCZERWIENI", ignoreCase = true)
        } ?: IrFolder("⚙ USTAWIENIA PODCZERWIENI")
        settingsFolder.name = "⚙ USTAWIENIA PODCZERWIENI"
        settingsFolder.colorHex = "#91A0B5"
        settingsFolder.iconName = "⚙️"
        settingsFolder.isListView = false

        recentFolder = pilotFolder.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name == "RECENTLY USED"
        } ?: irRemotesFolder.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name == "RECENTLY USED"
        } ?: allData.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name == "RECENTLY USED"
        } ?: IrFolder("RECENTLY USED")
        downloadedFolder = pilotFolder.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name == "DOWNLOADED"
        } ?: irRemotesFolder.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name == "DOWNLOADED"
        } ?: allData.items.filterIsInstance<IrFolder>().firstOrNull {
            it.name == "DOWNLOADED"
        } ?: IrFolder("DOWNLOADED")

        val existingLibraryItems = irRemotesFolder.items.toList()
        existingLibraryItems.forEach { item ->
            if (item !== pilotFolder && item !== settingsFolder &&
                item !== recentFolder && item !== downloadedFolder
            ) {
                if (item is IrFolder && item.name.contains("OPCJE", ignoreCase = true)) return@forEach
                if (item is Command && (
                        item.name.contains("Dodaj", ignoreCase = true) ||
                            item.name.contains("Importuj", ignoreCase = true) ||
                            item.name.contains("Eksportuj", ignoreCase = true) ||
                            item.name.contains("Kopia Zapasowa", ignoreCase = true)
                        )
                ) return@forEach
                pilotFolder.items.add(item)
            }
        }

        pilotFolder.items.removeAll {
            it is IrFolder && (it === recentFolder || it === downloadedFolder)
        }
        pilotFolder.items.add(0, downloadedFolder)
        pilotFolder.items.add(0, recentFolder)

        val settingsCommands = listOf(
            Triple(R.string.ir_settings_add_remote, "📁", "#4DA3FF"),
            Triple(R.string.ir_settings_add_command, "＋", "#43C59E"),
            Triple(R.string.ir_settings_import_file, "⇧", "#43C59E"),
            Triple(R.string.ir_settings_import_url, "🌐", "#43C59E"),
            Triple(R.string.ir_settings_export_backup, "📤", "#91A0B5"),
            Triple(R.string.ir_settings_restore_backup, "📥", "#91A0B5"),
            Triple(R.string.ir_settings_options, "⚙", "#F2B84B"),
            Triple(R.string.ir_settings_downloaded, "⇄", "#F2B84B"),
            Triple(R.string.ir_settings_tv_b_gone, "📺", "#F2B84B")
        )
        settingsFolder.items.clear()
        settingsFolder.items.addAll(settingsCommands.map { (title, icon, color) ->
            Command(getString(title), 0, intArrayOf(100, 100), icon, color)
        })

        fun containsIrSignal(folder: IrFolder): Boolean =
            folder.items.any { item ->
                when (item) {
                    is Command -> item.frequency > 0 && (item.pattern.isNotEmpty() || item.protocol != null) &&
                        item.name.isNotBlank() && item.iconName != "spacer"
                    is IrFolder -> containsIrSignal(item)
                    else -> false
                }
            }

        val legacyRemoteFolders = allData.items.filterIsInstance<IrFolder>().filter { folder ->
            val protectedRootFolder = listOf(
                "LAB SESSIONS",
                "LILYGO",
                "WPA CRACKER",
                "WEB FILE HUB",
                "USTAWIENIA"
            ).any { folder.name.contains(it, ignoreCase = true) }
            folder !== irRemotesFolder &&
                !protectedRootFolder &&
                !folder.name.equals("RECENTLY USED", ignoreCase = true) &&
                !folder.name.equals("DOWNLOADED", ignoreCase = true) &&
                containsIrSignal(folder)
        }
        (legacyRemoteFolders + listOf(recentFolder, downloadedFolder)).forEach { folder ->
            if (folder !== pilotFolder && folder !== settingsFolder &&
                pilotFolder.items.none { it === folder }
            ) {
                pilotFolder.items.add(folder)
            }
        }

        irRemotesFolder.items.clear()
        irRemotesFolder.items.add(pilotFolder)
        irRemotesFolder.items.add(settingsFolder)

        directIrLibraryRoot = irRemotesFolder
        directIrPilotFolder = pilotFolder
        directIrSettingsFolder = settingsFolder

        allData.items.removeAll { item ->
            item === irRemotesFolder || item === recentFolder || item === downloadedFolder ||
                legacyRemoteFolders.any { it === item }
        }
        allData.items.add(0, irRemotesFolder)
    }

    private fun playThemeSound() = ThemeSoundController.play(this)

    private fun stopThemeSound() = ThemeSoundController.pause()

    private fun releaseThemeSound() = ThemeSoundController.release()

    override fun onResume() {
        super.onResume()
        val oldPath = folderPathFromRoot(currentFolder)
        load()
        hasLoadedIrData = true
        currentFolder = resolveFolderPath(oldPath)
            ?: if (directIrLibraryMode) directIrLibraryRoot ?: allData else allData
        val route = findFolderRoute(allData, currentFolder).orEmpty()
        folderNavigationStack.clear()
        route.dropLast(1).forEach(folderNavigationStack::push)
        refreshList()
        runPendingWorkbenchActionIfReady()
        openPendingRemoteFolder()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("theme_sound_enabled", true)) {
            playThemeSound()
        }
        if (prefs.getBoolean("web_hub_enabled", false) && !AppWebFileServerController.isRunning()) {
            AppWebFileServerController.start(this)
        }
    }

    override fun onPause() {
        stopThemeSound()
        super.onPause()
    }

    override fun onDestroy() {
        BruceBleIrLink.setListener(null)
        releaseThemeSound()
        wifiAutoRefreshIntervalMs = 0L
        wifiAutoRefreshRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        wifiTargetDialog?.dismiss()
        wifiDetailDialog?.dismiss()
        wifiCaptureJob?.cancel()
        wifiCaptureDialog?.dismiss()
        clearPhoneWifiScan()
        super.onDestroy()
    }
}
