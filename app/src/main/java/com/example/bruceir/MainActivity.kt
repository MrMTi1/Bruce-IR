package com.example.bruceir

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.bruceir.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import java.net.URL
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var transmitter: IrTransmitter
    private lateinit var adapter: CommandAdapter
    private var allData = IrFolder("ROOT")
    private var currentFolder = allData
    private var recentFolder = IrFolder("RECENTLY USED")
    private var downloadedFolder = IrFolder("DOWNLOADED")
    private var isBruceOnline = false
    private var isListView = false
    private var isEditMode = false
    private lateinit var tvBGoneManager: TvBGoneManager
    private lateinit var macroManager: MacroManager
    private val gson = Gson()

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importBruceFile(it) }
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { performExport(it) }
    }
    private val importFullDbLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { performFullImport(it) }
    }
    private val pickAttackFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCustomAttack(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transmitter = IrTransmitter(this)
        tvBGoneManager = TvBGoneManager(this)
        macroManager = MacroManager(this, transmitter)

        setupNavigation()
        setupRemotes()
        setupCyberTools()
        setupSystemTools()
        setupHeaderActions()
        
        load()
        startHeartbeat()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentFolder != allData) {
                    currentFolder = findParent(allData, currentFolder) ?: allData
                    refreshList()
                } else finish()
            }
        })
    }

    private fun startHeartbeat() {
        val handler = Handler(Looper.getMainLooper())
        val checkTask = object : Runnable {
            override fun run() {
                Thread {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    var baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
                    if (!baseUrl.startsWith("http")) baseUrl = "http://$baseUrl"
                    val pingUrl = if (baseUrl.endsWith("/")) "${baseUrl}ping" else "$baseUrl/ping"

                    val online = try {
                        val conn = URL(pingUrl).openConnection()
                        conn.connectTimeout = 1000
                        conn.readTimeout = 1000
                        val responseCode = (conn as java.net.HttpURLConnection).responseCode
                        responseCode == 200
                    } catch (e: Exception) { false }
                    
                    runOnUiThread {
                        isBruceOnline = online
                        findViewById<View>(R.id.vStatusDot).setBackgroundColor(if (online) Color.GREEN else Color.RED)
                        if (currentFolder == allData) {
                            findViewById<TextView>(R.id.tvHeaderTitle).text = if (online) "BRUCE ONLINE" else "BRUCE OFFLINE"
                        }
                    }
                }.start()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(checkTask)
    }

    private fun setupNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val sRemotes = findViewById<View>(R.id.sectionRemotes)
        val sCyber = findViewById<View>(R.id.sectionCyber)
        val sSystem = findViewById<View>(R.id.sectionSystem)

        nav.setOnItemSelectedListener { item ->
            sRemotes.visibility = View.GONE
            sCyber.visibility = View.GONE
            sSystem.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_remotes -> sRemotes.visibility = View.VISIBLE
                R.id.nav_cyber -> sCyber.visibility = View.VISIBLE
                R.id.nav_system -> sSystem.visibility = View.VISIBLE
            }
            true
        }
    }

    private fun setupCyberTools() {
        findViewById<Button>(R.id.btnCyberTpms).setOnClickListener { showTpmsToolDialog() }
        findViewById<Button>(R.id.btnCyberSubGhz).setOnClickListener { startActivity(Intent(this, SubGhzActivity::class.java)) }
        findViewById<Button>(R.id.btnCyberImmo).setOnClickListener { showImmoToolDialog() }
        findViewById<Button>(R.id.btnCyberBle).setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "ble") }) }
        findViewById<Button>(R.id.btnCyberC2).setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "bridge") }) }
        findViewById<Button>(R.id.btnCyberWps).setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "wps") }) }
    }

    private fun setupSystemTools() {
        findViewById<Button>(R.id.btnSysNet).setOnClickListener { startActivity(Intent(this, NetworkScannerActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnSysConsole).setOnClickListener { startActivity(Intent(this, SerialConsoleActivity::class.java)) }
        findViewById<Button>(R.id.btnSysNav).setOnClickListener { startActivity(Intent(this, NavigatorActivity::class.java)) }
        findViewById<Button>(R.id.btnSysRemote).setOnClickListener { showRemoteConfigDialog() }
    }

    private fun showRemoteConfigDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val inputUrl = EditText(this).apply { hint = "IP Address"; setText("http://bruce.local") }
        layout.addView(inputUrl)
        AlertDialog.Builder(this).setTitle("Bruce URL").setView(layout).setPositiveButton("SAVE") { _, _ ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("bruce_url", inputUrl.text.toString()).apply()
        }.show()
    }

    private fun setupHeaderActions() {
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            val et = findViewById<EditText>(R.id.etSearch)
            if (et.visibility == View.VISIBLE) {
                et.visibility = View.GONE; et.setText(""); refreshList()
            } else {
                et.visibility = View.VISIBLE; et.requestFocus()
            }
        }
        findViewById<ImageButton>(R.id.btnViewMode).setOnClickListener {
            isListView = !isListView
            adapter.setListView(isListView)
            (it as ImageButton).setImageResource(if (isListView) android.R.drawable.ic_dialog_dialer else android.R.drawable.ic_menu_sort_by_size)
            val lm = findViewById<RecyclerView>(R.id.recyclerView).layoutManager as GridLayoutManager
            lm.spanCount = if (isListView) 1 else 3
            adapter.notifyDataSetChanged()
        }
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener {
            isEditMode = !isEditMode
            adapter.setEditMode(isEditMode)
            (it as ImageButton).setImageResource(if (isEditMode) android.R.drawable.ic_partial_secure else android.R.drawable.ic_secure)
        }
        findViewById<ImageView>(R.id.ivLogo).setOnClickListener { showInfoDialog() }
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAdd).setOnClickListener { showManagementMenu() }
        
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterList(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupRemotes() {
        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = CommandAdapter(currentFolder.items, null, { action, item, pos ->
            when (action) {
                CommandAdapter.ActionType.DELETE -> {
                    if (item == recentFolder || item == downloadedFolder) return@CommandAdapter
                    confirmAction(R.string.confirm_delete_msg) { currentFolder.items.removeAt(pos); save(); adapter.notifyDataSetChanged() }
                }
                CommandAdapter.ActionType.EDIT -> {
                    if (item == recentFolder || item == downloadedFolder) return@CommandAdapter
                    if (item is Command) showEditCmd(item, pos)
                    else if (item is IrFolder) showRenameFolder(item, pos)
                }
                CommandAdapter.ActionType.OPEN -> if (item is IrFolder) { currentFolder = item; refreshList() }
                CommandAdapter.ActionType.MOVE -> showMoveDialog(item, pos)
                CommandAdapter.ActionType.ADD_TO_MACRO -> if (item is Command) showMacroPickerForCommand(item)
            }
        }, { cmd ->
            transmitter.transmit(cmd.frequency, cmd.pattern)
            addToRecent(cmd)
            if (currentFolder == recentFolder) refreshList()
        })
        rv.layoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (isListView) 3 else 1
            }
        }
        rv.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun isLongPressDragEnabled(): Boolean = isEditMode
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition; val to = target.adapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(currentFolder.items, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh); save(); adapter.notifyDataSetChanged()
            }
        }).attachToRecyclerView(rv)
    }

    private fun load() {
        allData = BruceUtils.loadAllData(this); currentFolder = allData
        recentFolder = allData.items.find { it is IrFolder && it.name == "RECENTLY USED" } as? IrFolder ?: IrFolder("RECENTLY USED").also { allData.items.add(0, it) }
        downloadedFolder = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder ?: IrFolder("DOWNLOADED").also { allData.items.add(1, it) }
        refreshList()
    }

    private fun save(onDone: (() -> Unit)? = null) { BruceUtils.saveAllData(this, allData, onDone) }

    private fun refreshList() {
        adapter.updateList(currentFolder.items)
        findViewById<TextView>(R.id.tvHeaderTitle).text = if (currentFolder == allData) (if (isBruceOnline) "BRUCE ONLINE" else "BRUCE OFFLINE") else currentFolder.name
    }

    private fun showManagementMenu() {
        val ops = arrayOf(getString(R.string.import_op_bruce_file), getString(R.string.import_op_url), getString(R.string.import_op_folder), getString(R.string.import_op_manual), getString(R.string.import_op_export), getString(R.string.import_op_import))
        AlertDialog.Builder(this).setTitle(R.string.import_menu_title).setItems(ops) { _, w ->
            when (w) {
                0 -> pickFile.launch("*/*")
                1 -> showImportUrlDialog()
                2 -> showAddFolder()
                3 -> showAddManual()
                4 -> exportLauncher.launch("BruceIR_Backup.json")
                5 -> importFullDbLauncher.launch("application/json")
            }
        }.show()
    }

    private fun confirmAction(msgRes: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this).setTitle(R.string.confirm_title).setMessage(msgRes).setPositiveButton(R.string.dialog_ok) { _, _ -> onConfirm() }.setNegativeButton(R.string.dialog_cancel, null).show()
    }

    private fun showAddFolder() {
        val input = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this).setTitle(R.string.dialog_new_folder).setView(input).setPositiveButton("OK") { _, _ -> currentFolder.items.add(IrFolder(input.text.toString().uppercase())); save { runOnUiThread { refreshList() } } }.show()
    }

    private fun showAddManual() {
        val nameIn = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this).setTitle(R.string.dialog_manual_button).setView(nameIn).setPositiveButton("OK") { _, _ -> currentFolder.items.add(Command(nameIn.text.toString(), 38000, intArrayOf(100, 100))); save { runOnUiThread { refreshList() } } }.show()
    }

    private fun showEditCmd(cmd: Command, pos: Int) {
        val rootScroll = android.widget.ScrollView(this)
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        rootScroll.addView(lay)
        val nameIn = EditText(this).apply { setText(cmd.name) }; val dataIn = EditText(this).apply { setText(cmd.pattern.joinToString(" ")) }
        lay.addView(TextView(this).apply { text = getString(R.string.label_name) }); lay.addView(nameIn); lay.addView(TextView(this).apply { text = getString(R.string.label_raw_data); setPadding(0, 20, 0, 0) }); lay.addView(dataIn)
        
        val btnIcon = Button(this).apply {
            text = getString(R.string.btn_change_icon, cmd.iconName ?: "AUTO")
            setOnClickListener {
                val icons = arrayOf("AUTO", "POWER", "VOLUME", "MUTE", "PLAY", "PAUSE", "STOP", "UP", "DOWN", "LEFT", "RIGHT", "STAR", "HEART", "BRIGHTNESS", "CONTRAST", "HOME", "SETTINGS", "INFO", "CAMERA", "MIC", "SEARCH", "SEND", "LOCK", "UNLOCK", "LIGHT", "WIFI", "BATTERY", "AC", "FAN", "TV", "OK", "CANCEL", "PLUS", "MINUS")
                AlertDialog.Builder(context).setItems(icons) { _, i -> cmd.iconName = if (i == 0) null else icons[i]; this.text = getString(R.string.btn_change_icon, cmd.iconName ?: "AUTO") }.show()
            }
        }
        lay.addView(btnIcon)
        val btnColor = Button(this).apply { text = "ZMIEŃ KOLOR: ${cmd.colorHex ?: "AUTO"}"; setOnClickListener { val colorNames = arrayOf("AUTO", "RED", "BLUE", "GREEN", "YELLOW", "ORANGE", "PURPLE", "GREY", "BLACK"); val colorValues = arrayOf(null, "#E53935", "#1E88E5", "#43A047", "#FDD835", "#FB8C00", "#8E24AA", "#757575", "#212121"); AlertDialog.Builder(context).setItems(colorNames) { _, i -> cmd.colorHex = colorValues[i]; this.text = "ZMIEŃ KOLOR: ${cmd.colorHex ?: "AUTO"}" }.show() } }
        lay.addView(btnColor)
        val btnPin = Button(this).apply { text = getString(R.string.btn_pin); setOnClickListener { pinShortcut(cmd) } }
        lay.addView(btnPin)
        val btnMove = Button(this).apply { text = getString(R.string.btn_move); setOnClickListener { showMoveDialog(cmd, pos) } }
        lay.addView(btnMove)

        AlertDialog.Builder(this).setTitle(R.string.dialog_edit).setView(rootScroll).setPositiveButton(R.string.dialog_save) { _, _ -> try { cmd.name = nameIn.text.toString(); cmd.pattern = dataIn.text.toString().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray(); save { runOnUiThread { adapter.notifyItemChanged(pos) } } } catch (e: Exception) {} }.setNegativeButton(R.string.dialog_delete) { _, _ -> confirmAction(R.string.confirm_delete_msg) { currentFolder.items.removeAt(pos); save { runOnUiThread { refreshList() } } } }.show()
    }

    private fun pinShortcut(cmd: Command) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = getSystemService(ShortcutManager::class.java)
            if (sm.isRequestPinShortcutSupported) {
                val intent = Intent(this, MainActivity::class.java).apply { action = "com.example.bruceir.ACTION_SEND_IR"; putExtra("freq", cmd.frequency); putExtra("pattern", cmd.pattern); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                val pinShortcutInfo = ShortcutInfo.Builder(this, "id_${cmd.name}_${System.currentTimeMillis()}").setShortLabel(cmd.name).setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher)).setIntent(intent).build()
                sm.requestPinShortcut(pinShortcutInfo, null)
            }
        }
    }

    private fun showRenameFolder(f: IrFolder, pos: Int) {
        val input = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this).setTitle(R.string.dialog_rename).setView(input).setPositiveButton("OK") { _, _ -> f.name = input.text.toString().uppercase(); save { runOnUiThread { adapter.notifyItemChanged(pos) } } }.setNegativeButton(R.string.dialog_delete) { _, _ -> confirmAction(R.string.confirm_delete_msg) { currentFolder.items.removeAt(pos); save { runOnUiThread { refreshList() } } } }.show()
    }

    private fun filterList(query: String) {
        if (query.isEmpty()) adapter.updateList(currentFolder.items) else { val filtered = mutableListOf<Any>(); findInFolder(allData, query, filtered); adapter.updateList(filtered) }
    }

    private fun findInFolder(folder: IrFolder, query: String, results: MutableList<Any>) {
        folder.items.forEach { if (it is Command) { if (it.name.contains(query, ignoreCase = true)) results.add(it) } else if (it is IrFolder) { if (it.name.contains(query, ignoreCase = true)) results.add(it); findInFolder(it, query, results) } }
    }

    private fun showMoveDialog(item: Any, pos: Int) {
        val folders = mutableListOf<IrFolder>(); findAllFolders(allData, folders)
        if (item is IrFolder) { val toRemove = mutableListOf<IrFolder>(); findSubfolders(item, toRemove); toRemove.add(item); folders.removeAll(toRemove) }
        val names = folders.map { if (it == allData) getString(R.string.folder_root) else it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.btn_move).setItems(names) { _, which -> val target = folders[which]; if (target != currentFolder) { confirmAction(R.string.confirm_move_msg) { currentFolder.items.removeAt(pos); target.items.add(item); save { runOnUiThread { refreshList() } }; Toast.makeText(this, getString(R.string.toast_move_success, target.name), Toast.LENGTH_SHORT).show() } } }.show()
    }

    private fun findSubfolders(root: IrFolder, list: MutableList<IrFolder>) { root.items.forEach { if (it is IrFolder) { list.add(it); findSubfolders(it, list) } } }
    private fun findAllFolders(root: IrFolder, list: MutableList<IrFolder>) { if (root.name == "RECENTLY USED") return; list.add(root); root.items.forEach { if (it is IrFolder) findAllFolders(it, list) } }
    private fun findParent(root: IrFolder, target: IrFolder): IrFolder? { for (item in root.items) { if (item is IrFolder) { if (item == target) return root; val found = findParent(item, target); if (found != null) return found } } ; return null }

    private fun addToRecent(cmd: Command) {
        val iterator = recentFolder.items.iterator()
        while (iterator.hasNext()) { val item = iterator.next(); if (item is Command && item.name == cmd.name && item.pattern.contentEquals(cmd.pattern)) iterator.remove() }
        recentFolder.items.add(0, Command(cmd.name, cmd.frequency, cmd.pattern.copyOf()))
        if (recentFolder.items.size > 10) recentFolder.items.removeAt(recentFolder.items.size - 1)
        save()
    }

    private fun showInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        val tvIrStatus = view.findViewById<TextView>(R.id.tvIrStatus); val hasIr = transmitter.hasInternalIr()
        tvIrStatus.text = if (hasIr) getString(R.string.ir_status_supported) else getString(R.string.ir_status_not_supported)
        tvIrStatus.setTextColor(if (hasIr) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        val tvUsbStatus = view.findViewById<TextView>(R.id.tvUsbStatus); val hasUsb = transmitter.isUsbDeviceConnected()
        tvUsbStatus.text = if (hasUsb) getString(R.string.usb_ir_status_connected) else getString(R.string.usb_ir_status_disconnected)
        tvUsbStatus.setTextColor(if (hasUsb) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        val btnChangeMode = view.findViewById<Button>(R.id.btnChangeMode)
        btnChangeMode.text = "${getString(R.string.transmitter_mode_title)}: ${transmitter.currentMode.name}"
        btnChangeMode.setOnClickListener { val modes = IrTransmitter.Mode.values(); val modeNames = modes.map { when(it) { IrTransmitter.Mode.INTERNAL -> getString(R.string.mode_internal); IrTransmitter.Mode.USB -> getString(R.string.mode_usb); IrTransmitter.Mode.WIFI -> "Bruce WiFi" } }.toTypedArray(); AlertDialog.Builder(this).setTitle(R.string.transmitter_mode_title).setItems(modeNames) { _, i -> transmitter.currentMode = modes[i]; btnChangeMode.text = "${getString(R.string.transmitter_mode_title)}: ${transmitter.currentMode.name}"; Toast.makeText(this, "Tryb: ${modeNames[i]}", Toast.LENGTH_SHORT).show() }.show() }
        val swSound = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swClickSound); val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        swSound.isChecked = prefs.getBoolean("click_sound", true)
        swSound.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("click_sound", isChecked).apply(); adapter.setSoundEnabled(isChecked) }
        view.findViewById<TextView>(R.id.tvGithubLink).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MrMTi1/Bruce-IR/blob/master"))) }
        view.findViewById<Button>(R.id.btnChangeLang).setOnClickListener { val currentLocales = AppCompatDelegate.getApplicationLocales(); val newLocale = if (currentLocales.isEmpty || currentLocales.get(0)?.language == "en") "pl" else "en"; AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLocale)) }
        AlertDialog.Builder(this).setView(view).setPositiveButton(R.string.dialog_close, null).show()
    }

    private fun showImmoToolDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val tvStatus = TextView(this).apply { text = getString(R.string.immo_detecting) }; layout.addView(tvStatus)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        AlertDialog.Builder(this).setTitle(R.string.immo_title).setView(layout).setPositiveButton(R.string.immo_scan) { _, _ -> Thread { val url = "$baseUrl/rfid/scan"; val response = BruceUtils.downloadFileContent(url, "admin", "bruce"); runOnUiThread { if (response != null) AlertDialog.Builder(this).setTitle("RFID Tag Found").setMessage(response).setPositiveButton("OK", null).show() } }.start() }.show()
    }

    private fun showTpmsToolDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val etId = EditText(this).apply { hint = "Sensor ID (Hex)"; setText("A1B2C3D4") }; val etPress = EditText(this).apply { hint = "Pressure (Bar)"; setText("0.8") }
        layout.addView(etId); layout.addView(etPress)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
        AlertDialog.Builder(this).setTitle("TPMS SENSOR SPOOF").setView(layout).setPositiveButton("EMULATE ALARM") { _, _ -> Thread { val url = "$baseUrl/rf/tpms?id=${etId.text}&press=${etPress.text}&status=alert"; BruceUtils.downloadFileContent(url, "admin", "bruce") }.start() }.setNeutralButton("SCAN", { _, _ -> Toast.makeText(this, "Bruce is listening for TPMS packets...", Toast.LENGTH_LONG).show() }).show()
    }

    private fun showTvBGoneDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
        val text1 = dialogView.findViewById<TextView>(android.R.id.text1); val text2 = dialogView.findViewById<TextView>(android.R.id.text2)
        text1.text = getString(R.string.tvgone_mode); text2.text = getString(R.string.tvgone_ready)
        val dialog = AlertDialog.Builder(this).setTitle("TV-B-Gone").setView(dialogView).setPositiveButton(R.string.tvgone_start, null).setNegativeButton(R.string.tvgone_stop) { _, _ -> tvBGoneManager.stop() }.setNeutralButton("MENU", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false; tvBGoneManager.start({ current, total, name -> text1.post { text1.text = getString(R.string.tvgone_progress, current, total); text2.text = getString(R.string.tvgone_code, name) } }, { text1.post { text1.text = getString(R.string.tvgone_finished); text2.text = ""; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true } }) }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { val ops = arrayOf(getString(R.string.tvgone_load_file), "Add from Database", "Reset to Default"); AlertDialog.Builder(this).setItems(ops) { _, w -> when(w) { 0 -> pickAttackFile.launch("*/*"); 1 -> showCommandPicker(allData) { cmd -> val currentList = tvBGoneManager.getAttackList().toMutableList(); currentList.add(IrCommand(cmd.name, cmd.frequency, cmd.pattern)); tvBGoneManager.setAttackList(currentList); Toast.makeText(this, "Added: ${cmd.name}", Toast.LENGTH_SHORT).show() }; 2 -> { tvBGoneManager.setAttackList(emptyList()); Toast.makeText(this, "Reset to built-in codes", Toast.LENGTH_SHORT).show() } } }.show() }
    }

    private fun showMacroListDialog() {
        val macros = macroManager.getAllMacros(); val names = macros.map { it.name }.toMutableList(); names.add("+ " + getString(R.string.macro_new))
        AlertDialog.Builder(this).setTitle(R.string.macro_select).setItems(names.toTypedArray()) { _, which -> if (which == macros.size) showNewMacroDialog() else showMacroDetailsDialog(macros[which]) }.setNegativeButton(R.string.dialog_close, null).show()
    }

    private fun showNewMacroDialog() {
        val input = EditText(this).apply { hint = "Macro Name" }
        AlertDialog.Builder(this).setTitle(R.string.macro_new).setView(input).setPositiveButton(R.string.dialog_ok) { _, _ -> val name = input.text.toString(); if (name.isNotEmpty()) { val newMacro = MacroSet(name); macroManager.updateMacro(newMacro); showMacroDetailsDialog(newMacro) } }.setNegativeButton(R.string.dialog_cancel, null).show()
    }

    private fun showMacroDetailsDialog(macro: MacroSet) {
        val names = macro.commands.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle(macro.name).setItems(names) { _, which -> showMacroItemOptions(macro, which) }.setPositiveButton(R.string.macro_start) { _, _ -> startMacroExecution(macro) }.setNeutralButton(R.string.macro_add) { _, _ -> showCommandPicker(allData) { selectedCmd -> macro.commands.add(selectedCmd); macroManager.updateMacro(macro); showMacroDetailsDialog(macro) } }.setNegativeButton(R.string.macro_config) { _, _ -> showMacroConfigDialog(macro) }.setNeutralButton("MENU", { _, _ -> showMacroExtraMenu(macro) }).show()
    }

    private fun showMacroExtraMenu(macro: MacroSet) {
        val ops = arrayOf(getString(R.string.macro_rename), getString(R.string.macro_delete_all), getString(R.string.macro_clear), getString(R.string.macro_move_up), getString(R.string.macro_move_down))
        AlertDialog.Builder(this).setItems(ops) { _, w -> val all = macroManager.getAllMacros(); val idx = all.indexOfFirst { it.name == macro.name }; when(w) { 0 -> { val input = EditText(this).apply { setText(macro.name) }; AlertDialog.Builder(this).setTitle(R.string.macro_rename).setView(input).setPositiveButton("OK") { _, _ -> val oldName = macro.name; macro.name = input.text.toString(); all.removeAll { it.name == oldName }; all.add(macro); macroManager.saveAllMacros(all); showMacroDetailsDialog(macro) }.show() }; 1 -> confirmAction(R.string.confirm_delete_msg) { all.removeAll { it.name == macro.name }; macroManager.saveAllMacros(all); showMacroListDialog() }; 2 -> confirmAction(R.string.confirm_title) { macro.commands.clear(); macroManager.updateMacro(macro); showMacroDetailsDialog(macro) }; 3 -> if (idx > 0) { Collections.swap(all, idx, idx - 1); macroManager.saveAllMacros(all); showMacroListDialog() }; 4 -> if (idx != -1 && idx < all.size - 1) { Collections.swap(all, idx, idx + 1); macroManager.saveAllMacros(all); showMacroListDialog() } } }.show()
    }

    private fun showMacroItemOptions(macro: MacroSet, index: Int) {
        val options = arrayOf(getString(R.string.macro_move_up), getString(R.string.macro_move_down), getString(R.string.macro_remove_item))
        AlertDialog.Builder(this).setTitle(macro.commands[index].name).setItems(options) { _, which -> when (which) { 0 -> if (index > 0) { Collections.swap(macro.commands, index, index - 1); macroManager.updateMacro(macro); showMacroDetailsDialog(macro) }; 1 -> if (index < macro.commands.size - 1) { Collections.swap(macro.commands, index, index + 1); macroManager.updateMacro(macro); showMacroDetailsDialog(macro) }; 2 -> { macro.commands.removeAt(index); macroManager.updateMacro(macro); showMacroDetailsDialog(macro) } } }.show()
    }

    private fun showMacroConfigDialog(macro: MacroSet) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
        val cbLoop = CheckBox(this).apply { text = getString(R.string.macro_loop); isChecked = macro.isLooping }; val tvDelay = TextView(this).apply { text = getString(R.string.macro_delay) }; val etDelay = EditText(this).apply { setText(macro.delayMs.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(cbLoop); layout.addView(tvDelay); layout.addView(etDelay)
        AlertDialog.Builder(this).setTitle(R.string.macro_config).setView(layout).setPositiveButton(R.string.dialog_save) { _, _ -> macro.isLooping = cbLoop.isChecked; macro.delayMs = etDelay.text.toString().toLongOrNull() ?: 600L; macroManager.updateMacro(macro); showMacroDetailsDialog(macro) }.setNegativeButton(R.string.dialog_cancel, null).show()
    }

    private fun startMacroExecution(macro: MacroSet) {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
        val t1 = dialogView.findViewById<TextView>(android.R.id.text1); val t2 = dialogView.findViewById<TextView>(android.R.id.text2)
        val progressDialog = AlertDialog.Builder(this).setTitle(if (macro.isLooping) "Makra (LOOP)..." else "Macro...").setView(dialogView).setNegativeButton(R.string.tvgone_stop) { _, _ -> macroManager.stop() }.setCancelable(false).show()
        macroManager.start(macro, { cur, total, name -> t1.post { t1.text = getString(R.string.macro_progress, cur, total); t2.text = getString(R.string.macro_sending, name) } }, { cmd -> addToRecent(cmd); runOnUiThread { if (currentFolder == recentFolder) refreshList() } }, { t1.post { progressDialog.dismiss(); Toast.makeText(this, R.string.toast_macro_done, Toast.LENGTH_SHORT).show() } })
    }

    private fun showMacroPickerForCommand(cmd: Command) {
        val macros = macroManager.getAllMacros(); if (macros.isEmpty()) { val m = MacroSet("MACRO 1"); m.commands.add(cmd); macroManager.updateMacro(m); Toast.makeText(this, "Added to MACRO 1", Toast.LENGTH_SHORT).show(); return }
        val names = macros.map { it.name }.toTypedArray(); AlertDialog.Builder(this).setTitle(R.string.btn_add_to_macro).setItems(names) { _, which -> macros[which].commands.add(cmd); macroManager.updateMacro(macros[which]); Toast.makeText(this, getString(R.string.toast_move_success, macros[which].name), Toast.LENGTH_SHORT).show() }.show()
    }

    private fun showCommandPicker(folder: IrFolder, onPicked: (Command) -> Unit) {
        val items = folder.items; val names = items.map { if (it is IrFolder) "[FOLDER] ${it.name}" else (it as Command).name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Pick from ${folder.name}").setItems(names) { _, which -> val selected = items[which]; if (selected is IrFolder) showCommandPicker(selected, onPicked) else if (selected is Command) onPicked(selected) }.setNegativeButton(R.string.dialog_cancel, null).show()
    }

    private fun loadCustomAttack(uri: Uri) {
        try { contentResolver.openInputStream(uri)?.use { inputStream -> val fileName = uri.lastPathSegment?.lowercase() ?: ""; val list = mutableListOf<IrCommand>()
                if (fileName.endsWith(".json")) { val reader = inputStream.bufferedReader(); val folder = BruceUtils.streamParseJson(reader); val allCmds = mutableListOf<Command>(); extractCommands(folder, allCmds); allCmds.forEach { list.add(IrCommand(it.name, it.frequency, it.pattern)) } } else { val content = inputStream.bufferedReader().readText(); val cmds = BruceUtils.parseIrContent(content); cmds.forEach { list.add(IrCommand(it.name, it.frequency, it.pattern)) } }
                if (list.isNotEmpty()) { tvBGoneManager.setAttackList(list); Toast.makeText(this, R.string.toast_signal_sent, Toast.LENGTH_SHORT).show(); showTvBGoneDialog() } else { Toast.makeText(this, "No IR codes found", Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { Toast.makeText(this, "Load error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun extractCommands(folder: IrFolder, list: MutableList<Command>) { folder.items.forEach { if (it is Command) list.add(it) else if (it is IrFolder) extractCommands(it, list) } }
    private fun showImportUrlDialog() { val input = EditText(this).apply { hint = "https://raw.githubusercontent.com/.../file.ir"; setText("https://raw.githubusercontent.com/MrMTi1/Bruce-IR/master/Ir_codes.json") }; AlertDialog.Builder(this).setTitle(R.string.import_op_url).setView(input).setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> val url = input.text.toString().trim(); if (url.isNotEmpty()) fetchAndImportIr(url) }.setNegativeButton(R.string.dialog_cancel, null).show() }
    private fun fetchAndImportIr(url: String) {
        val finalUrl = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/"); Thread { try { val connection = java.net.URL(finalUrl).openConnection() as java.net.HttpURLConnection; connection.connectTimeout = 5000; val content = connection.inputStream.bufferedReader().use { it.readText() }; val fileName = finalUrl.substringAfterLast("/").substringBefore("?").uppercase(); runOnUiThread { if (finalUrl.endsWith(".json", ignoreCase = true)) { try { allData = BruceUtils.streamParseJson(content.reader()); currentFolder = allData; save { runOnUiThread { refreshList() } } } catch (e: Exception) { processIrContent(content, fileName) } } else { processIrContent(content, fileName) } } } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() } } }.start()
    }
    private fun processIrContent(content: String, folderName: String) { val commands = BruceUtils.parseIrContent(content); if (commands.isNotEmpty()) { downloadedFolder.items.add(IrFolder(folderName, commands.toMutableList() as MutableList<Any>)); save { runOnUiThread { refreshList() } }; Toast.makeText(this, getString(R.string.toast_imported, "DOWNLOADED", folderName), Toast.LENGTH_SHORT).show() } }
    private fun performExport(uri: Uri) { try { val json = gson.toJson(allData); contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }; Toast.makeText(this, R.string.toast_export_success, Toast.LENGTH_SHORT).show() } catch (e: Exception) {} }
    private fun performFullImport(uri: Uri) { try { contentResolver.openInputStream(uri)?.use { inputStream -> allData = BruceUtils.streamParseJson(inputStream.bufferedReader()); currentFolder = allData; save { runOnUiThread { refreshList() } } }; Toast.makeText(this, R.string.toast_import_success, Toast.LENGTH_SHORT).show() } catch (e: Exception) {} }

    private fun importBruceFile(uri: Uri) {
        try { contentResolver.openInputStream(uri)?.bufferedReader()?.use { r -> val content = r.readText(); val fName = uri.lastPathSegment?.substringAfterLast("/")?.uppercase()?.replace(".IR", "") ?: "IMPORT"; processIrContent(content, fName) } } catch (e: Exception) {}
    }

    private fun findFolderByName(root: IrFolder, name: String): IrFolder? { if (root.name == name) return root; root.items.forEach { if (it is IrFolder) { val found = findFolderByName(it, name); if (found != null) return found } }; return null }
    override fun onResume() { super.onResume(); val oldFolderId = currentFolder.name; load(); currentFolder = findFolderByName(allData, oldFolderId) ?: allData; refreshList() }
}
