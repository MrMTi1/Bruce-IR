package com.example.bruceir

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import java.net.URL
import java.util.Collections
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

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importBruceFile(it) } }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { performExport(it) } }
    private val importFullDbLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { performFullImport(it) } }
    private val pickAttackFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { loadCustomAttack(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transmitter = IrTransmitter(this)
        tvBGoneManager = TvBGoneManager(this)
        macroManager = MacroManager(this, transmitter)

        setupNavigation()
        setupMainToolBar()
        setupCyberTools()
        setupSystemTools()
        setupHeaderActions()
        
        load()
        setupRemotes() // Initializing the adapter before it's used
        refreshList()
        heartbeatHandler.post(checkTask)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentFolder != allData) { currentFolder = findParent(allData, currentFolder) ?: allData; refreshList() } else finish()
            }
        })
    }

    private fun setupNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val sRemotes = findViewById<View>(R.id.sectionRemotes)
        val sCyber = findViewById<View>(R.id.sectionCyber)
        val sSystem = findViewById<View>(R.id.sectionSystem)

        nav.setOnItemSelectedListener { item ->
            sRemotes.visibility = View.GONE; sCyber.visibility = View.GONE; sSystem.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_remotes -> sRemotes.visibility = View.VISIBLE
                R.id.nav_cyber -> sCyber.visibility = View.VISIBLE
                R.id.nav_system -> sSystem.visibility = View.VISIBLE
            }
            true
        }
    }

    private fun setupMainToolBar() {
        findViewById<ImageButton>(R.id.btnTvBGone).setOnClickListener { showTvBGoneDialog() }
        findViewById<ImageButton>(R.id.btnMacro).setOnClickListener { showMacroListDialog() }
        findViewById<ImageButton>(R.id.btnLux).setOnClickListener { startActivity(Intent(this, LuxActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnIntercom).setOnClickListener { startActivity(Intent(this, IntercomActivity::class.java)) }
    }

    private fun setupCyberTools() {
        findViewById<Button>(R.id.btnCyberTpms).setOnClickListener { startActivity(Intent(this, RfAnalyzerActivity::class.java).apply { putExtra("mode", "tpms") }) }
        findViewById<Button>(R.id.btnCyberSubGhz).setOnClickListener { startActivity(Intent(this, RfAnalyzerActivity::class.java).apply { putExtra("mode", "subghz") }) }
        findViewById<Button>(R.id.btnCyberImmo).setOnClickListener { showImmoToolDialog() }
        findViewById<Button>(R.id.btnCyberBle).setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "ble") }) }
        findViewById<Button>(R.id.btnCyberC2).setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "bridge") }) }
        findViewById<Button>(R.id.btnCyberWps).setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java).apply { putExtra("target", "wps") }) }
    }

    private fun setupSystemTools() {
        findViewById<Button>(R.id.btnSysNet).setOnClickListener { startActivity(Intent(this, NetworkScannerActivity::class.java)) }
        findViewById<ImageButton>(R.id.btnSysConsole).setOnClickListener { startActivity(Intent(this, SerialConsoleActivity::class.java)) }
        findViewById<Button>(R.id.btnSysNav).setOnClickListener { startActivity(Intent(this, NavigatorActivity::class.java)) }
        findViewById<Button>(R.id.btnSysRemote).setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
    }

    private fun setupHeaderActions() {
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            val et = findViewById<EditText>(R.id.etSearch)
            if (et.visibility == View.VISIBLE) { et.visibility = View.GONE; et.setText(""); refreshList() } else { et.visibility = View.VISIBLE; et.requestFocus() }
        }
        findViewById<ImageButton>(R.id.btnViewMode).setOnClickListener {
            isListView = !isListView
            adapter.setListView(isListView)
            (it as ImageButton).setImageResource(if (isListView) android.R.drawable.ic_dialog_dialer else android.R.drawable.ic_menu_sort_by_size)
            (findViewById<RecyclerView>(R.id.recyclerView).layoutManager as GridLayoutManager).spanCount = if (isListView) 1 else 3
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

    private var heartbeatHandler = Handler(Looper.getMainLooper())
    private val checkTask = object : Runnable {
        override fun run() {
            Thread {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                val baseUrl = prefs.getString("bruce_url", "http://bruce.local") ?: "http://bruce.local"
                val user = prefs.getString("bruce_user", "admin") ?: "admin"
                val pass = prefs.getString("bruce_pass", "bruce") ?: "bruce"
                var fUrl = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
                if (!fUrl.endsWith("/")) fUrl += "/"
                val online = try {
                    val conn = URL(fUrl).openConnection() as java.net.HttpURLConnection
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        val auth = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
                        conn.setRequestProperty("Authorization", "Basic $auth")
                    }
                    conn.connectTimeout = 1500; conn.readTimeout = 1500
                    val isOnline = conn.responseCode in 200..404
                    if (isOnline) {
                        val live = BruceUtils.downloadFileContent(fUrl + "live", user, pass)
                        if (live != null && (live.contains("\"ir\"") || live.contains("\"rf\""))) runOnUiThread { showLiveCaptureDialog(live) }
                    }
                    isOnline
                } catch (e: Exception) { false }
                runOnUiThread {
                    isBruceOnline = online
                    findViewById<View>(R.id.vStatusDot).setBackgroundColor(if (online) Color.GREEN else Color.RED)
                    if (currentFolder == allData) findViewById<TextView>(R.id.tvHeaderTitle).text = if (online) "BRUCE ONLINE" else "BRUCE OFFLINE"
                    findViewById<TextView>(R.id.tvHeaderTitle).setTextColor(if (online) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
                }
            }.start()
            heartbeatHandler.postDelayed(this, 3000)
        }
    }

    private fun showLiveCaptureDialog(json: String) {
        AlertDialog.Builder(this).setTitle("LIVE SIGNAL").setMessage("Bruce captured a signal! Save it?").setPositiveButton("EDIT") { _, _ -> Toast.makeText(this, "Editor coming soon", Toast.LENGTH_SHORT).show() }.setNegativeButton("IGNORE", null).show()
    }

    private fun load() {
        allData = BruceUtils.loadAllData(this); currentFolder = allData
        recentFolder = allData.items.find { it is IrFolder && it.name == "RECENTLY USED" } as? IrFolder ?: IrFolder("RECENTLY USED").also { allData.items.add(0, it) }
        downloadedFolder = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder ?: IrFolder("DOWNLOADED").also { allData.items.add(1, it) }
    }

    private fun refreshList() {
        adapter.updateList(currentFolder.items)
        findViewById<TextView>(R.id.tvHeaderTitle).text = if (currentFolder == allData) (if (isBruceOnline) "BRUCE ONLINE" else "BRUCE OFFLINE") else currentFolder.name
    }

    private fun setupRemotes() {
        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = CommandAdapter(currentFolder.items, null, { action, item, pos ->
            when (action) {
                CommandAdapter.ActionType.DELETE -> { if (item != recentFolder && item != downloadedFolder) confirmAction(R.string.confirm_delete_msg) { currentFolder.items.removeAt(pos); save(); adapter.notifyDataSetChanged() } }
                CommandAdapter.ActionType.EDIT -> { if (item != recentFolder && item != downloadedFolder) { if (item is Command) showEditCmd(item, pos) else if (item is IrFolder) showRenameFolder(item, pos) } }
                CommandAdapter.ActionType.OPEN -> if (item is IrFolder) { currentFolder = item; refreshList() }
                CommandAdapter.ActionType.MOVE -> showMoveDialog(item, pos)
                CommandAdapter.ActionType.ADD_TO_MACRO -> if (item is Command) addToMacro(item)
            }
        }, { cmd -> transmitter.transmit(cmd.frequency, cmd.pattern); addToRecent(cmd); if (currentFolder == recentFolder) refreshList() })
        rv.layoutManager = GridLayoutManager(this, 3).apply { spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int): Int = if (isListView) 3 else 1 } }
        rv.adapter = adapter
    }

    private fun addToMacro(cmd: Command) {
        val macros = macroManager.getAllMacros()
        if (macros.isEmpty()) {
            macros.add(MacroSet("DEFAULT MACRO"))
            macroManager.saveAllMacros(macros)
        }
        
        val options = macros.map { it.name }.toMutableList()
        options.add(0, "+ NEW MACRO")
        
        AlertDialog.Builder(this)
            .setTitle("Add to Macro")
            .setItems(options.toTypedArray()) { _, w ->
                if (w == 0) {
                    showCreateMacroAndAdd(cmd)
                } else {
                    val target = macros[w - 1]
                    target.commands.add(cmd.copy())
                    macroManager.updateMacro(target)
                    Toast.makeText(this, "Added to ${target.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showCreateMacroAndAdd(cmd: Command) {
        val input = EditText(this).apply { hint = "New Macro Name" }
        AlertDialog.Builder(this)
            .setTitle("Create Macro")
            .setView(input)
            .setPositiveButton("Create & Add") { _, _ ->
                val name = input.text.toString().uppercase()
                if (name.isNotEmpty()) {
                    val newMacro = MacroSet(name)
                    newMacro.commands.add(cmd.copy())
                    macroManager.updateMacro(newMacro)
                    Toast.makeText(this, "Created $name and added command", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(onDone: (() -> Unit)? = null) { BruceUtils.saveAllData(this, allData, onDone) }
    private fun addToRecent(cmd: Command) {
        recentFolder.items.removeAll { it is Command && it.name == cmd.name && it.pattern.contentEquals(cmd.pattern) }
        recentFolder.items.add(0, Command(cmd.name, cmd.frequency, cmd.pattern.copyOf()))
        if (recentFolder.items.size > 10) recentFolder.items.removeAt(recentFolder.items.size - 1)
        save()
    }

    private fun showManagementMenu() {
        val ops = arrayOf("Load .ir", "Import URL", "New Folder", "Manual Add", "Export DB", "Import DB")
        AlertDialog.Builder(this).setTitle("Management").setItems(ops) { _, w ->
            when (w) { 0 -> pickFile.launch("*/*"); 1 -> showImportUrlDialog(); 2 -> showAddFolder(); 3 -> showAddManual(); 4 -> exportLauncher.launch("BruceIR_Backup.json"); 5 -> importFullDbLauncher.launch("application/json") }
        }.show()
    }

    private fun confirmAction(msgRes: Int, onConfirm: () -> Unit) { AlertDialog.Builder(this).setTitle("Confirm").setMessage(msgRes).setPositiveButton("OK") { _, _ -> onConfirm() }.setNegativeButton("Cancel", null).show() }
    private fun showAddFolder() { val input = EditText(this); AlertDialog.Builder(this).setTitle("New Folder").setView(input).setPositiveButton("OK") { _, _ -> currentFolder.items.add(IrFolder(input.text.toString().uppercase())); save { runOnUiThread { refreshList() } } }.show() }
    private fun showAddManual() { val input = EditText(this); AlertDialog.Builder(this).setTitle("Manual Add").setView(input).setPositiveButton("OK") { _, _ -> currentFolder.items.add(Command(input.text.toString(), 38000, intArrayOf(100, 100))); save { runOnUiThread { refreshList() } } }.show() }

    private fun showEditCmd(cmd: Command, pos: Int) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        val nameIn = EditText(this).apply { setText(cmd.name) }; val dataIn = EditText(this).apply { setText(cmd.pattern.joinToString(" ")) }
        lay.addView(TextView(this).apply { text = "Name" }); lay.addView(nameIn); lay.addView(TextView(this).apply { text = "RAW Data" }); lay.addView(dataIn)
        AlertDialog.Builder(this).setTitle("Edit").setView(lay).setPositiveButton("Save") { _, _ -> try { cmd.name = nameIn.text.toString(); cmd.pattern = dataIn.text.toString().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray(); save { runOnUiThread { adapter.notifyItemChanged(pos) } } } catch (e: Exception) {} }.show()
    }

    private fun showRenameFolder(f: IrFolder, pos: Int) {
        val input = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input).setPositiveButton("OK") { _, _ -> f.name = input.text.toString().uppercase(); save { runOnUiThread { adapter.notifyItemChanged(pos) } } }.show()
    }

    private fun filterList(query: String) { if (query.isEmpty()) adapter.updateList(currentFolder.items) else { val filtered = mutableListOf<Any>(); findInFolder(allData, query, filtered); adapter.updateList(filtered) } }
    private fun findInFolder(folder: IrFolder, query: String, results: MutableList<Any>) { folder.items.forEach { if (it is Command) { if (it.name.contains(query, ignoreCase = true)) results.add(it) } else if (it is IrFolder) { if (it.name.contains(query, ignoreCase = true)) results.add(it); findInFolder(it, query, results) } } }
    private fun showMoveDialog(item: Any, pos: Int) {
        val folders = mutableListOf<IrFolder>(); findAllFolders(allData, folders)
        val names = folders.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Move").setItems(names) { _, w -> val target = folders[w]; if (target != currentFolder) { currentFolder.items.removeAt(pos); target.items.add(item); save { runOnUiThread { refreshList() } } } }.show()
    }

    private fun findAllFolders(root: IrFolder, list: MutableList<IrFolder>) { list.add(root); root.items.forEach { if (it is IrFolder) findAllFolders(it, list) } }
    private fun findParent(root: IrFolder, target: IrFolder): IrFolder? { for (item in root.items) { if (item is IrFolder) { if (item == target) return root; val f = findParent(item, target); if (f != null) return f } } ; return null }

    private fun showInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        view.findViewById<Button>(R.id.btnChangeLang).setOnClickListener { val cur = AppCompatDelegate.getApplicationLocales(); val next = if (cur.isEmpty || cur.get(0)?.language == "en") "pl" else "en"; AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next)) }
        
        val swInstant = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swInstantBoot)
        swInstant.isChecked = prefs.getBoolean("instant_boot", false)
        swInstant.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("instant_boot", isChecked).apply()
        }

        AlertDialog.Builder(this).setView(view).setPositiveButton("Close", null).show()
    }

    private fun showImmoToolDialog() { Toast.makeText(this, "RFID/IMMO Tool active", Toast.LENGTH_SHORT).show() }
    private fun showTpmsToolDialog() { Toast.makeText(this, "TPMS Tool active", Toast.LENGTH_SHORT).show() }

    private fun showTvBGoneDialog() { tvBGoneManager.start({ _, _, _ -> }, { }) }

    private fun loadCustomAttack(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val content = inputStream.bufferedReader().use { it.readText() }
            val list = BruceUtils.parseIrContent(content).map { IrCommand(it.name, it.frequency, it.pattern) }
            if (list.isNotEmpty()) {
                tvBGoneManager.setAttackList(list)
                Toast.makeText(this, "Attack list updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMacroListDialog() { startActivity(Intent(this, MacroListActivity::class.java)) }

    private fun showImportUrlDialog() {
        val input = EditText(this).apply { setText("https://raw.githubusercontent.com/MrMTi1/Bruce-IR/master/Ir_codes.json") }
        AlertDialog.Builder(this).setTitle("Import URL").setView(input).setPositiveButton("OK") { _, _ -> fetchAndImportIr(input.text.toString()) }.show()
    }

    private fun fetchAndImportIr(url: String) {
        Thread { try { val content = URL(url).readText(); runOnUiThread { processIrContent(content, "URL_IMPORT") } } catch (e: Exception) {} }.start()
    }

    private fun processIrContent(content: String, folderName: String) {
        val cmds = BruceUtils.parseIrContent(content)
        if (cmds.isNotEmpty()) { downloadedFolder.items.add(IrFolder(folderName, cmds.toMutableList() as MutableList<Any>)); save { runOnUiThread { refreshList() } } }
    }

    private fun performExport(uri: Uri) { try { contentResolver.openOutputStream(uri)?.use { it.write(gson.toJson(allData).toByteArray()) } } catch (e: Exception) {} }
    private fun performFullImport(uri: Uri) { try { contentResolver.openInputStream(uri)?.use { allData = BruceUtils.streamParseJson(it.bufferedReader()); currentFolder = allData; save { runOnUiThread { refreshList() } } } } catch (e: Exception) {} }
    private fun importBruceFile(uri: Uri) { try { contentResolver.openInputStream(uri)?.bufferedReader()?.use { processIrContent(it.readText(), "FILE_IMPORT") } } catch (e: Exception) {} }
    private fun showMacroPickerForCommand(cmd: Command) { Toast.makeText(this, "Added ${cmd.name} to Macro", Toast.LENGTH_SHORT).show() }
    private fun findFolderByName(root: IrFolder, name: String): IrFolder? { if (root.name == name) return root; root.items.forEach { if (it is IrFolder) { val f = findFolderByName(it, name); if (f != null) return f } }; return null }
    override fun onResume() { super.onResume(); val old = currentFolder.name; load(); currentFolder = findFolderByName(allData, old) ?: allData; refreshList() }
}
