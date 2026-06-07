package com.example.bruceir

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.hardware.ConsumerIrManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.bruceir.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("settings", MODE_PRIVATE)
        val lang = prefs.getString("lang", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private var irManager: ConsumerIrManager? = null
    private lateinit var adapter: CommandAdapter
    private var allData = IrFolder("ROOT") 
    private var currentFolder = allData    
    private var recentFolder = IrFolder("RECENTLY USED")
    private var downloadedFolder = IrFolder("DOWNLOADED")
    private val gson = Gson()
    private var isEditMode = false 
    private var isListView = false
    private lateinit var tvBGoneManager: TvBGoneManager
    private lateinit var acGoneManager: AcGoneManager
    private lateinit var acTurboManager: AcTurboManager
    private lateinit var macroManager: MacroManager

    // Launcher 1: Wybór pojedynczego pliku .ir (Bruce/Flipper)
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importBruceFile(it) }
    }

    // Launcher 2: Eksport całej bazy do pliku JSON
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { performExport(it) }
    }

    // Launcher 3: Import całej bazy z pliku JSON (Kopia zapasowa)
    private val importFullDbLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { performFullImport(it) }
    }

    // Launcher 4: Wybór pliku ataku TV-B-Gone
    private val pickAttackFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCustomAttack(it, isAc = false) }
    }

    private val pickAcAttackFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCustomAttack(it, isAc = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        tvBGoneManager = TvBGoneManager(irManager)
        acGoneManager = AcGoneManager(irManager)
        acTurboManager = AcTurboManager(irManager)
        macroManager = MacroManager(this, irManager)

        // Obsługa skrótu (Pin to Home)
        if (intent?.action == "com.example.bruceir.ACTION_SEND_IR") {
            val freq = intent.getIntExtra("freq", 38000)
            val pattern = intent.getIntArrayExtra("pattern")
            if (pattern != null) {
                irManager?.transmit(freq, pattern)
                Toast.makeText(this, R.string.toast_signal_sent, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)

        // Usuwamy wymuszony biały kolor paska stanu, pozwalamy systemowi/tematowi zdecydować
        try {
            // WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        } catch (e: Exception) {}

        load() // Wczytaj bazę z pamięci telefonu

        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        val lm = GridLayoutManager(this, 3)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (isListView) 3 else 1
            }
        }
        rv.itemAnimator = null // Wyłączamy animacje dla trybu SWAP

        adapter = CommandAdapter(currentFolder.items, irManager, { action, item, pos ->
            when (action) {
                CommandAdapter.ActionType.DELETE -> {
                    if (item == recentFolder || item == downloadedFolder) return@CommandAdapter
                    currentFolder.items.removeAt(pos)
                    save()
                    adapter.notifyDataSetChanged()
                }
                CommandAdapter.ActionType.EDIT -> {
                    if (item == recentFolder || item == downloadedFolder) return@CommandAdapter
                    if (item is Command) showEditCmd(item, pos)
                    else if (item is IrFolder) showRenameFolder(item, pos)
                }
                CommandAdapter.ActionType.OPEN -> {
                    if (item is IrFolder) {
                        currentFolder = item
                        refreshList()
                    }
                }
                CommandAdapter.ActionType.MOVE -> {
                    showMoveDialog(item, pos)
                }
                CommandAdapter.ActionType.ADD_TO_MACRO -> {
                    if (item is Command) {
                        val macro = macroManager.getMacro()
                        macro.add(item)
                        macroManager.saveMacro(macro)
                        Toast.makeText(this, R.string.btn_add_to_macro, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, { cmd ->
            addToRecent(cmd)
            if (currentFolder == recentFolder) refreshList()
        })

        rv.layoutManager = lm
        rv.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = isEditMode
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(currentFolder.items, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                save() 
                adapter.notifyDataSetChanged() 
            }
        })
        touchHelper.attachToRecyclerView(rv)

        // Przycisk kłódki
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener {
            isEditMode = !isEditMode
            adapter.setEditMode(isEditMode)
            (it as ImageButton).setImageResource(if (isEditMode) android.R.drawable.ic_partial_secure else android.R.drawable.ic_secure)
        }

        findViewById<ImageButton>(R.id.btnLux).setOnClickListener {
            startActivity(Intent(this, LuxActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnRemote).setOnClickListener {
            showRemoteDialog()
        }

        findViewById<ImageButton>(R.id.btnMacro).setOnClickListener {
            showMacroDialog()
        }

        findViewById<ImageButton>(R.id.btnAcTurbo).setOnClickListener {
            showAcTurboDialog()
        }

        findViewById<ImageButton>(R.id.btnViewMode).setOnClickListener {
            isListView = !isListView
            adapter.setListView(isListView)
            val rv = findViewById<RecyclerView>(R.id.recyclerView)
            
            // Odświeżamy span assignments, aby spanSizeLookup zadziałał z nową wartością isListView

            (it as ImageButton).setImageResource(if (isListView) android.R.drawable.ic_dialog_dialer else android.R.drawable.ic_menu_sort_by_size)
        }

        findViewById<ImageButton>(R.id.btnTvBGone).setOnClickListener { showTvBGoneDialog() }
        findViewById<ImageButton>(R.id.btnAcGone).setOnClickListener { showAcGoneDialog() }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showAddMenu() }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            val et = findViewById<EditText>(R.id.etSearch)
            if (et.visibility == View.VISIBLE) {
                et.visibility = View.GONE
                et.setText("")
                refreshList()
            } else {
                et.visibility = View.VISIBLE
                et.requestFocus()
            }
        }

        findViewById<ImageView>(R.id.ivLogo).setOnClickListener {
            showInfoDialog()
        }

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentFolder != allData) {
                    currentFolder = findParent(allData, currentFolder) ?: allData
                    refreshList()
                } else finish()
            }
        })
        refreshList()
    }

    private fun showTvBGoneDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
        val text1 = dialogView.findViewById<TextView>(android.R.id.text1)
        val text2 = dialogView.findViewById<TextView>(android.R.id.text2)
        
        text1.text = "Tryb: Wbudowane kody"
        text2.text = "Gotowy do ataku (Power OFF)"

        val dialog = AlertDialog.Builder(this)
            .setTitle("TV-B-Gone")
            .setView(dialogView)
            .setPositiveButton("START", null)
            .setNegativeButton("ZATRZYMAJ") { _, _ -> tvBGoneManager.stop() }
            .setNeutralButton("WCZYTAJ PLIK .IR", null)
            .show()

        val startBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val loadBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            tvBGoneManager.start({ current, total, name ->
                text1.text = "Atak: $current / $total"
                text2.text = "Kod: $name"
            }, {
                text1.text = "Atak zakończony!"
                text2.text = "Wszystkie kody zostały wysłane."
                startBtn.isEnabled = true
            })
        }

        loadBtn.setOnClickListener {
            pickAttackFile.launch("*/*")
            dialog.dismiss()
        }
    }

    private fun showAcGoneDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
        val text1 = dialogView.findViewById<TextView>(android.R.id.text1)
        val text2 = dialogView.findViewById<TextView>(android.R.id.text2)
        
        text1.text = "Tryb: Wbudowane (Power OFF)"
        text2.text = "Gotowy do wyłączenia klimatyzacji"

        val dialog = AlertDialog.Builder(this)
            .setTitle("AC-Gone (Power OFF)")
            .setView(dialogView)
            .setPositiveButton("START", null)
            .setNegativeButton("ZATRZYMAJ") { _, _ -> acGoneManager.stop() }
            .setNeutralButton("WCZYTAJ PLIK .IR", null)
            .show()

        val startBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val loadBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            acGoneManager.start({ current, total, name ->
                text1.text = "Atak AC: $current / $total"
                text2.text = "Urządzenie: $name"
            }, {
                text1.text = "Zakończono!"
                text2.text = "Wszystkie kody AC Gone wysłane."
                startBtn.isEnabled = true
            })
        }

        loadBtn.setOnClickListener {
            pickAcAttackFile.launch("*/*")
            dialog.dismiss()
        }
    }

    private fun showMacroDialog() {
        val currentMacro = macroManager.getMacro()
        if (currentMacro.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.macro_title)
                .setMessage(R.string.macro_empty)
                .setNeutralButton(R.string.macro_add) { _, _ ->
                    showCommandPicker(allData) { selectedCmd ->
                        currentMacro.add(selectedCmd)
                        macroManager.saveMacro(currentMacro)
                        showMacroDialog()
                    }
                }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
            return
        }

        val names = currentMacro.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.macro_title)
            .setItems(names) { _, which ->
                showMacroItemOptions(currentMacro, which)
            }
            .setPositiveButton(R.string.macro_start) { _, _ ->
                showMacroConfigDialog()
            }
            .setNeutralButton(R.string.macro_add) { _, _ ->
                showCommandPicker(allData) { selectedCmd ->
                    currentMacro.add(selectedCmd)
                    macroManager.saveMacro(currentMacro)
                    showMacroDialog()
                }
            }
            .setNegativeButton(R.string.macro_clear) { _, _ ->
                macroManager.saveMacro(emptyList())
                Toast.makeText(this, R.string.macro_clear, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showMacroItemOptions(macro: MutableList<Command>, index: Int) {
        val options = arrayOf("↑ Przesuń w górę", "↓ Przesuń w dół", "🗑 Usuń")
        AlertDialog.Builder(this)
            .setTitle(macro[index].name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Up
                        if (index > 0) {
                            Collections.swap(macro, index, index - 1)
                            macroManager.saveMacro(macro)
                            showMacroDialog()
                        }
                    }
                    1 -> { // Down
                        if (index < macro.size - 1) {
                            Collections.swap(macro, index, index + 1)
                            macroManager.saveMacro(macro)
                            showMacroDialog()
                        }
                    }
                    2 -> { // Delete
                        macro.removeAt(index)
                        macroManager.saveMacro(macro)
                        showMacroDialog()
                    }
                }
            }
            .show()
    }

    private fun showMacroConfigDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val cbLoop = CheckBox(this).apply { text = "Zapętlij (Loop)" }
        val tvDelay = TextView(this).apply { text = "Opóźnienie między komendami (ms):" }
        val etDelay = EditText(this).apply { 
            hint = "600"
            setText("600")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(cbLoop)
        layout.addView(tvDelay)
        layout.addView(etDelay)

        AlertDialog.Builder(this)
            .setTitle("Konfiguracja Makra")
            .setView(layout)
            .setPositiveButton("START") { _, _ ->
                val delay = etDelay.text.toString().toLongOrNull() ?: 600L
                val isLoop = cbLoop.isChecked
                startMacroExecution(delay, isLoop)
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun startMacroExecution(delay: Long = 600L, isLooping: Boolean = false) {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
        val t1 = dialogView.findViewById<TextView>(android.R.id.text1)
        val t2 = dialogView.findViewById<TextView>(android.R.id.text2)
        
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(if (isLooping) "Makra (PĘTLA)..." else "Wykonywanie Makra...")
            .setView(dialogView)
            .setNegativeButton("STOP") { _, _ -> macroManager.stop() }
            .setCancelable(false)
            .show()

        macroManager.start(delay, isLooping, { cur, total, name ->
            t1.text = "Komenda $cur / $total"
            t2.text = "Wysyłam: $name"
        }, {
            progressDialog.dismiss()
            Toast.makeText(this, R.string.toast_macro_done, Toast.LENGTH_SHORT).show()
        })
    }

    private fun showCommandPicker(folder: IrFolder, onPicked: (Command) -> Unit) {
        val items = folder.items
        val names = items.map { 
            if (it is IrFolder) "[FOLDER] ${it.name}" else (it as Command).name 
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Wybierz z ${folder.name}")
            .setItems(names) { _, which ->
                val selected = items[which]
                if (selected is IrFolder) {
                    showCommandPicker(selected, onPicked)
                } else if (selected is Command) {
                    onPicked(selected)
                }
            }
            .setNegativeButton("Wróć", null)
            .show()
    }

    private fun showAcTurboDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
        val text1 = dialogView.findViewById<TextView>(android.R.id.text1)
        val text2 = dialogView.findViewById<TextView>(android.R.id.text2)

        text1.text = "Tryb: Wbudowane (Turbo Mode)"
        text2.text = "Gotowy do wysłania komend Turbo"

        val dialog = AlertDialog.Builder(this)
            .setTitle("AC Turbo: MAX COOLING")
            .setView(dialogView)
            .setPositiveButton("START", null)
            .setNegativeButton("ZATRZYMAJ") { _, _ -> acTurboManager.stop() }
            .show()

        val startBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            acTurboManager.start({ current, total, name ->
                text1.text = "Wysyłanie Turbo: $current / $total"
                text2.text = "Kod: $name"
            }, {
                text1.text = "Zakończono!"
                text2.text = "Wszystkie kody Turbo wysłane."
                startBtn.isEnabled = true
            })
        }
    }

    private fun loadCustomAttack(uri: Uri, isAc: Boolean) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = uri.lastPathSegment?.lowercase() ?: ""
                val list = mutableListOf<IrCommand>()

                if (fileName.endsWith(".json")) {
                    val reader = inputStream.bufferedReader()
                    val folder = BruceUtils.streamParseJson(reader)
                    val allCmds = mutableListOf<Command>()
                    extractCommands(folder, allCmds)
                    allCmds.forEach { list.add(IrCommand(it.name, it.frequency, it.pattern)) }
                } else {
                    val content = inputStream.bufferedReader().readText()
                    val cmds = BruceUtils.parseIrContent(content)
                    cmds.forEach { list.add(IrCommand(it.name, it.frequency, it.pattern)) }
                }

                if (list.isNotEmpty()) {
                    if (isAc) {
                        acGoneManager.setAttackList(list)
                        Toast.makeText(this, R.string.toast_signal_sent, Toast.LENGTH_SHORT).show()
                        showAcGoneDialog()
                    } else {
                        tvBGoneManager.setAttackList(list)
                        Toast.makeText(this, R.string.toast_signal_sent, Toast.LENGTH_SHORT).show()
                        showTvBGoneDialog()
                    }
                } else {
                    Toast.makeText(this, "No IR codes found", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Load error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractCommands(folder: IrFolder, list: MutableList<Command>) {
        folder.items.forEach {
            if (it is Command) list.add(it)
            else if (it is IrFolder) extractCommands(it, list)
        }
    }

    private fun showAddMenu() {
        val ops = arrayOf(
            getString(R.string.import_op_bruce_file),
            getString(R.string.import_op_url),
            getString(R.string.import_op_auto),
            getString(R.string.import_op_webui_manual),
            getString(R.string.import_op_folder),
            getString(R.string.import_op_manual),
            getString(R.string.import_op_export),
            getString(R.string.import_op_import)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.import_menu_title)
            .setItems(ops) { _, w ->
                when (w) {
                    0 -> pickFile.launch("*/*")
                    1 -> showImportUrlDialog()
                    2 -> showAutoImportDialog()
                    3 -> showWebUiImportDialog()
                    4 -> showAddFolder()
                    5 -> showAddManual()
                    6 -> exportLauncher.launch("BruceIR_Backup.json")
                    7 -> importFullDbLauncher.launch("application/json")
                }
            }.show()
    }

    private fun showWebUiImportDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val inputUrl = EditText(this).apply { hint = "http://bruce.local"; setText("http://bruce.local") }
        val inputPath = EditText(this).apply { hint = "/sd/tv.ir"; setText("/") }
        val inputUser = EditText(this).apply { hint = "admin"; setText("admin") }
        val inputPass = EditText(this).apply { hint = "bruce"; setText("bruce") }
        
        layout.addView(TextView(this).apply { text = "URL:" }); layout.addView(inputUrl)
        layout.addView(TextView(this).apply { text = "Path:" }); layout.addView(inputPath)
        layout.addView(TextView(this).apply { text = "User:" }); layout.addView(inputUser)
        layout.addView(TextView(this).apply { text = "Pass:" }); layout.addView(inputPass)

        AlertDialog.Builder(this)
            .setTitle(R.string.import_op_webui_manual)
            .setView(layout)
            .setPositiveButton(R.string.remote_connect) { _, _ ->
                val baseUrl = inputUrl.text.toString().trim().removeSuffix("/")
                val filePath = inputPath.text.toString().trim()
                fetchWebUiFile(baseUrl, filePath, inputUser.text.toString(), inputPass.text.toString())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun fetchWebUiFile(baseUrl: String, filePath: String, user: String, pass: String) {
        Thread {
            try {
                var content = BruceUtils.downloadFileContent("$baseUrl/download?path=$filePath", user, pass)
                if (content == null || !content.contains("name:")) {
                    content = BruceUtils.downloadFileContent("$baseUrl/download?file=$filePath", user, pass)
                }

                if (content != null && content.contains("name:")) {
                    val fileName = filePath.substringAfterLast("/").uppercase().replace(".IR", "")
                    runOnUiThread { processIrContent(content, fileName) }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Error: Download failed or invalid format", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Bruce WebUI Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showAutoImportDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val inputUrl = EditText(this).apply { hint = "http://bruce.local"; setText("http://bruce.local") }
        val inputUser = EditText(this).apply { hint = "admin"; setText("admin") }
        val inputPass = EditText(this).apply { hint = "bruce"; setText("bruce") }
        
        layout.addView(TextView(this).apply { text = "URL:" }); layout.addView(inputUrl)
        layout.addView(TextView(this).apply { text = "User:" }); layout.addView(inputUser)
        layout.addView(TextView(this).apply { text = "Pass:" }); layout.addView(inputPass)

        AlertDialog.Builder(this)
            .setTitle(R.string.auto_import_title)
            .setMessage(R.string.auto_import_desc)
            .setView(layout)
            .setPositiveButton(R.string.auto_import_scan) { _, _ ->
                val baseUrl = inputUrl.text.toString().trim().removeSuffix("/")
                startAutoImport(baseUrl, inputUser.text.toString(), inputPass.text.toString())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startAutoImport(baseUrl: String, user: String, pass: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Auto-Import")
            .setMessage("Łączenie z Bruce...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                val allFiles = mutableListOf<String>()
                val roots = listOf("/SD", "/LittleFS", "/", "/sd", "/ir", "/spiffs", "/data", "/fs", "/www", "/download", "/upload", "/flash", "/internal", "/codes")
                
                roots.forEach { p ->
                    runOnUiThread { progressDialog.setMessage("Skanowanie: $p") }
                    scanFiles(baseUrl, p, user, pass, allFiles)
                }
                
                val irFiles = allFiles.filter { it.lowercase().endsWith(".ir") }.distinct()
                
                if (irFiles.isEmpty()) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        val msg = if (lastErrorCode == 404) 
                            "Błąd 404: Twoje urządzenie Bruce ma inny interfejs WebUI (nie wspiera /list lub /fs). Spróbuj zaimportować pliki ręcznie przez Remote Connect."
                            else if (lastErrorCode != 0 && lastErrorCode != 200) 
                            "Błąd połączenia ($lastErrorCode: $lastErrorMsg). Sprawdź IP i Hasło." 
                            else "Połączono, ale nie znaleziono żadnych plików .ir."
                        AlertDialog.Builder(this@MainActivity).setTitle("Import nieudany").setMessage(msg).setPositiveButton("OK", null).show()
                    }
                    return@Thread
                }

                runOnUiThread { progressDialog.setMessage("Pobieranie ${irFiles.size} plików do DOWNLOADED...") }

                var importedCount = 0
                irFiles.forEach { filePath ->
                    var content = BruceUtils.downloadFileContent("$baseUrl/download?path=$filePath", user, pass)
                    if (content == null || !content.contains("name:")) {
                        content = BruceUtils.downloadFileContent("$baseUrl/download?file=$filePath", user, pass)
                    }

                    if (content != null && content.contains("name:") && content.contains("data:")) {
                        val fileName = filePath.substringAfterLast("/").uppercase()
                        val commands = BruceUtils.parseIrContent(content)
                        if (commands.isNotEmpty()) {
                            downloadedFolder.items.add(IrFolder(fileName, commands.toMutableList() as MutableList<Any>))
                            importedCount++
                        }
                    }
                }

                runOnUiThread {
                    save()
                    refreshList()
                    progressDialog.dismiss()
                    Toast.makeText(this, getString(R.string.toast_move_success, "DOWNLOADED") + " ($importedCount)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Błąd krytyczny: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun scanFiles(baseUrl: String, dir: String, user: String, pass: String, fileList: MutableList<String>) {
        val listUrls = mutableListOf(
            "$baseUrl/list?dir=$dir", 
            "$baseUrl/fs?dir=$dir", 
            "$baseUrl/api/files?path=$dir",
            "$baseUrl/api/list?dir=$dir",
            "$baseUrl/files?dir=$dir",
            "$baseUrl/explorer/list?dir=$dir",
            "$baseUrl/api/v1/files?dir=$dir",
            "$baseUrl/storage/list?path=$dir",
            "$baseUrl/files/list?dir=$dir"
        )
        
        // Obsługa specyficznego formatu z parametrem drive=SD
        if (dir.startsWith("/SD", ignoreCase = true)) {
            val pathOnly = dir.removePrefix("/SD").ifEmpty { "/" }
            listUrls.add(0, "$baseUrl/list?drive=SD&path=${Uri.encode(pathOnly)}")
            listUrls.add(1, "$baseUrl/fs?drive=SD&path=${Uri.encode(pathOnly)}")
            listUrls.add(2, "$baseUrl/?drive=SD&path=${Uri.encode(pathOnly)}")
        }

        listUrls.forEach { url ->
            val response = BruceUtils.downloadFileContent(url, user, pass) ?: return@forEach
            
            try {
                // Sposób 1: JSON
                if (response.trim().startsWith("[")) {
                    val list = gson.fromJson<List<Map<String, Any>>>(response, object : TypeToken<List<Map<String, Any>>>() {}.type)
                    list.forEach { item ->
                        val name = (item["name"] ?: item["filename"] ?: item["text"]) as? String ?: ""
                        if (name == "." || name == "..") return@forEach
                        
                        val type = (item["type"] as? String ?: "").lowercase()
                        val isDir = (item["isDir"] as? Boolean) 
                            ?: (item["isdir"] as? Boolean)
                            ?: (type == "directory" || type == "dir" || type == "folder")
                        
                        val fullPath = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
                        
                        if (isDir) {
                            scanFiles(baseUrl, fullPath, user, pass, fileList)
                        } else if (name.lowercase().endsWith(".ir")) {
                            fileList.add(fullPath)
                        }
                    }
                } else if (response.contains("<li") || response.contains("<a href=")) {
                    // Sposób 2: HTML
                    val pattern = java.util.regex.Pattern.compile("href=\"([^\"]+)\"")
                    val matcher = pattern.matcher(response)
                    while (matcher.find()) {
                        val link = matcher.group(1) ?: continue
                        if (link.startsWith("?") || link.contains("..")) continue
                        
                        val name = link.removeSuffix("/").substringAfterLast("/")
                        val isDir = link.endsWith("/")
                        val fullPath = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
                        
                        if (isDir) {
                            scanFiles(baseUrl, fullPath, user, pass, fileList)
                        } else if (name.lowercase().endsWith(".ir")) {
                            fileList.add(fullPath)
                        }
                    }
                } else {
                    // Sposób 3: Tekstowy
                    response.lines().forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty()) {
                            val isDir = t.startsWith("D:") || t.contains("/") || !t.contains(".")
                            val name = t.substringAfter(":").removePrefix("/")
                            if (name == "." || name == "..") return@forEach
                            
                            val fullPath = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
                            if (isDir) scanFiles(baseUrl, fullPath, user, pass, fileList)
                            else if (name.lowercase().endsWith(".ir")) fileList.add(fullPath)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BruceIR", "Error parsing scan response from $url", e)
            }
        }
    }

    private var lastErrorCode: Int = 0
    private var lastErrorMsg: String = ""

    private fun showImportUrlDialog() {
        val input = EditText(this).apply { 
            hint = "https://raw.githubusercontent.com/.../file.ir"
            setText("https://raw.githubusercontent.com/MrMTi1/Bruce-IR/master/Ir_codes.json")
        }
        AlertDialog.Builder(this)
            .setTitle("Importuj z URL")
            .setView(input)
            .setPositiveButton("Importuj") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    fetchAndImportIr(url)
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun fetchAndImportIr(url: String) {
        val finalUrl = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
        Thread {
            try {
                val connection = java.net.URL(finalUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                val content = connection.inputStream.bufferedReader().use { it.readText() }
                val fileName = finalUrl.substringAfterLast("/").substringBefore("?").uppercase()
                
                runOnUiThread {
                    if (finalUrl.endsWith(".json", ignoreCase = true)) {
                        try {
                            val newRoot = BruceUtils.streamParseJson(content.reader())
                            allData = newRoot
                            currentFolder = allData
                            save()
                            refreshList()
                            Toast.makeText(this, "Pełny przywrócony z URL: ${allData.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            processIrContent(content, fileName)
                        }
                    } else {
                        processIrContent(content, fileName)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Błąd pobierania: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun processIrContent(content: String, folderName: String) {
        val commands = BruceUtils.parseIrContent(content)
        if (commands.isNotEmpty()) {
            downloadedFolder.items.add(IrFolder(folderName, commands.toMutableList() as MutableList<Any>))
            save()
            refreshList()
            Toast.makeText(this, getString(R.string.toast_imported, "DOWNLOADED", folderName), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No IR commands found", Toast.LENGTH_SHORT).show()
        }
    }

    // --- LOGIKA EKSPORTU ---
    private fun performExport(uri: Uri) {
        try {
            val json = gson.toJson(allData)
            contentResolver.openOutputStream(uri)?.use { 
                it.write(json.toByteArray())
            }
            Toast.makeText(this, R.string.toast_export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd eksportu: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- LOGIKA IMPORTU ---
    private fun performFullImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = inputStream.bufferedReader()
                allData = BruceUtils.streamParseJson(reader)
                currentFolder = allData
                save()
                refreshList()
            }
            Toast.makeText(this, R.string.toast_import_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd importu bazy: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("BruceIR", "Import OOM Fix Error", e)
        }
    }

    private fun load() {
        allData = BruceUtils.loadAllData(this)
        currentFolder = allData
        
        recentFolder = allData.items.find { it is IrFolder && it.name == "RECENTLY USED" } as? IrFolder ?: IrFolder("RECENTLY USED").also { allData.items.add(0, it) }
        downloadedFolder = allData.items.find { it is IrFolder && it.name == "DOWNLOADED" } as? IrFolder ?: IrFolder("DOWNLOADED").also { allData.items.add(1, it) }
    }

    private fun save() {
         BruceUtils.saveAllData(this, allData)
    }

    private fun importBruceFile(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val content = r.readText()
                val fName = uri.lastPathSegment?.substringAfterLast("/")?.uppercase() ?: "IMPORT"
                processIrContent(content, fName)
            }
        } catch (e: Exception) {}
    }

    private fun addToRecent(cmd: Command) {
        val iterator = recentFolder.items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is Command && item.name == cmd.name && item.pattern.contentEquals(cmd.pattern)) {
                iterator.remove()
            }
        }
        val copy = Command(cmd.name, cmd.frequency, cmd.pattern.copyOf())
        recentFolder.items.add(0, copy)
        if (recentFolder.items.size > 10) {
            recentFolder.items.removeAt(recentFolder.items.size - 1)
        }
        save()
    }

    private fun refreshList() {
        adapter.updateList(currentFolder.items)
        findViewById<TextView>(R.id.tvHeaderTitle).text = if (currentFolder == allData) "BruceIr By MTi" else currentFolder.name
    }

    private fun showAddFolder() {
        val input = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this).setTitle("New Folder").setView(input).setPositiveButton("OK") { _, _ ->
            currentFolder.items.add(IrFolder(input.text.toString().uppercase()))
            save(); refreshList()
        }.show()
    }

    private fun showAddManual() {
        val nameIn = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this).setTitle("Manual Button").setView(nameIn).setPositiveButton("OK") { _, _ ->
            currentFolder.items.add(Command(nameIn.text.toString(), 38000, intArrayOf(100, 100)))
            save(); refreshList()
        }.show()
    }

    private fun showEditCmd(cmd: Command, pos: Int) {
        val rootScroll = android.widget.ScrollView(this)
        val lay = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40) 
        }
        rootScroll.addView(lay)

        val nameIn = EditText(this).apply { setText(cmd.name) }
        val dataIn = EditText(this).apply { setText(cmd.pattern.joinToString(" ")) }
        
        lay.addView(TextView(this).apply { text = getString(R.string.label_name) })
        lay.addView(nameIn)
        lay.addView(TextView(this).apply { text = getString(R.string.label_raw_data); setPadding(0, 20, 0, 0) })
        lay.addView(dataIn)
        
        // Separator
        lay.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 30, 0, 30) }; setBackgroundColor(Color.LTGRAY) })

        val btnIcon = Button(this).apply {
            text = getString(R.string.btn_change_icon, cmd.iconName ?: "AUTO")
            setOnClickListener {
                val icons = arrayOf("AUTO", "POWER", "VOLUME", "MUTE", "PLAY", "PAUSE", "STOP", "UP", "DOWN", "LEFT", "RIGHT", "STAR", "HEART", "BRIGHTNESS", "CONTRAST", "HOME", "SETTINGS", "INFO", "CAMERA", "MIC", "SEARCH", "SEND", "LOCK", "UNLOCK", "LIGHT", "WIFI", "BATTERY", "AC", "FAN", "TV", "OK", "CANCEL", "PLUS", "MINUS")
                AlertDialog.Builder(context).setItems(icons) { _, i ->
                    cmd.iconName = if (i == 0) null else icons[i]
                    this.text = getString(R.string.btn_change_icon, cmd.iconName ?: "AUTO")
                }.show()
            }
        }
        lay.addView(btnIcon)

        val btnColor = Button(this).apply {
            text = "ZMIEŃ KOLOR: ${cmd.colorHex ?: "AUTO"}"
            setOnClickListener {
                val colorNames = arrayOf("AUTO", "RED", "BLUE", "GREEN", "YELLOW", "ORANGE", "PURPLE", "GREY", "BLACK")
                val colorValues = arrayOf(null, "#E53935", "#1E88E5", "#43A047", "#FDD835", "#FB8C00", "#8E24AA", "#757575", "#212121")
                AlertDialog.Builder(context).setItems(colorNames) { _, i ->
                    cmd.colorHex = colorValues[i]
                    this.text = "ZMIEŃ KOLOR: ${cmd.colorHex ?: "AUTO"}"
                }.show()
            }
        }
        lay.addView(btnColor)

        val btnPin = Button(this).apply { 
            text = getString(R.string.btn_pin)
            setOnClickListener { pinShortcut(cmd) }
        }
        lay.addView(btnPin)

        val btnMove = Button(this).apply {
            text = getString(R.string.btn_move)
            setOnClickListener { showMoveDialog(cmd, pos) }
        }
        lay.addView(btnMove)

        val btnAddMacro = Button(this).apply {
            text = getString(R.string.btn_add_to_macro)
            setOnClickListener {
                val macro = macroManager.getMacro()
                macro.add(cmd)
                macroManager.saveMacro(macro)
                Toast.makeText(context, R.string.btn_add_to_macro, Toast.LENGTH_SHORT).show()
            }
        }
        lay.addView(btnAddMacro)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_edit)
            .setView(rootScroll)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                try {
                    cmd.name = nameIn.text.toString()
                    cmd.pattern = dataIn.text.toString().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray()
                    save(); adapter.notifyItemChanged(pos)
                } catch (e: Exception) {}
            }.setNegativeButton(R.string.dialog_delete) { _, _ ->
                currentFolder.items.removeAt(pos); save(); adapter.notifyDataSetChanged()
            }.show()
    }

    private fun pinShortcut(cmd: Command) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = getSystemService(ShortcutManager::class.java)
            if (sm.isRequestPinShortcutSupported) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = "com.example.bruceir.ACTION_SEND_IR"
                    putExtra("freq", cmd.frequency)
                    putExtra("pattern", cmd.pattern)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val pinShortcutInfo = ShortcutInfo.Builder(this, "id_${cmd.name}_${System.currentTimeMillis()}")
                    .setShortLabel(cmd.name)
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .build()
                sm.requestPinShortcut(pinShortcutInfo, null)
            }
        } else {
            Toast.makeText(this, "Twoja wersja Androida nie wspiera przypinania skrótów.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameFolder(f: IrFolder, pos: Int) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
        val input = EditText(this).apply { setText(f.name) }
        lay.addView(input)

        val btnMove = Button(this).apply {
            text = "PRZENIEŚ CAŁY FOLDER"
            setOnClickListener { showMoveDialog(f, pos) }
        }
        lay.addView(btnMove)

        AlertDialog.Builder(this).setTitle("Rename/Move").setView(lay).setPositiveButton("OK") { _, _ ->
            f.name = input.text.toString().uppercase(); save(); adapter.notifyItemChanged(pos)
        }.setNegativeButton("Delete") { _, _ ->
            currentFolder.items.removeAt(pos); save(); adapter.notifyDataSetChanged()
        }.show()
    }

    private fun filterList(query: String) {
        if (query.isEmpty()) {
            adapter.updateList(currentFolder.items)
        } else {
            val filtered = mutableListOf<Any>()
            findInFolder(allData, query, filtered)
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

    private fun showMoveDialog(item: Any, pos: Int) {
        val folders = mutableListOf<IrFolder>()
        findAllFolders(allData, folders)
        
        // Zabezpieczenie: usuń folder który przenosisz i jego podfoldery z listy celów
        if (item is IrFolder) {
            val toRemove = mutableListOf<IrFolder>()
            findSubfolders(item, toRemove)
            toRemove.add(item)
            folders.removeAll(toRemove)
        }

        val names = folders.map { if (it == allData) "GLÓWNY (ROOT)" else it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Przenieś do folderu")
            .setItems(names) { d, which ->
                val target = folders[which]
                if (target != currentFolder) {
                    currentFolder.items.removeAt(pos)
                    target.items.add(item)
                    save()
                    refreshList()
                    Toast.makeText(this, getString(R.string.toast_move_success, target.name), Toast.LENGTH_SHORT).show()
                    d.dismiss()
                } else {
                    Toast.makeText(this, R.string.toast_already_there, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun findSubfolders(root: IrFolder, list: MutableList<IrFolder>) {
        root.items.forEach {
            if (it is IrFolder) {
                list.add(it)
                findSubfolders(it, list)
            }
        }
    }

    private fun findAllFolders(root: IrFolder, list: MutableList<IrFolder>) {
        if (root.name == "RECENTLY USED") return
        list.add(root)
        root.items.forEach { if (it is IrFolder) findAllFolders(it, list) }
    }

    private fun findParent(root: IrFolder, target: IrFolder): IrFolder? {
        for (item in root.items) {
            if (item is IrFolder) {
                if (item == target) return root
                val found = findParent(item, target)
                if (found != null) return found
            }
        }
        return null
    }

        override fun onResume() {
        super.onResume()
        // Odśwież dane przy powrocie (np. z BruceRemoteActivity po pobraniu pliku)
        val oldFolderId = currentFolder.name
        load()
        // Próbujemy wrócić do folderu, w którym byliśmy
        currentFolder = findFolderByName(allData, oldFolderId) ?: allData
        refreshList()
    }

    private fun findFolderByName(root: IrFolder, name: String): IrFolder? {
        if (root.name == name) return root
        root.items.forEach {
            if (it is IrFolder) {
                val found = findFolderByName(it, name)
                if (found != null) return found
            }
        }
        return null
    }

    private fun showInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        
        view.findViewById<TextView>(R.id.tvGithubLink).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MrMTi1/Bruce-IR/blob/master/kody"))
            startActivity(intent)
        }
        
        view.findViewById<Button>(R.id.btnChangeLang).setOnClickListener {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val currentLang = prefs.getString("lang", "en")
            val newLang = if (currentLang == "pl") "en" else "pl"
            
            prefs.edit().putString("lang", newLang).apply()
            
            // Restart aplikacji, aby zastosować zmiany
            val intent = Intent(this, SplashActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
        
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun showRemoteDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val inputUrl = EditText(this).apply { hint = "Device URL (e.g. http://192.168.1.100)"; setText("http://bruce.local") }
        val inputUser = EditText(this).apply { hint = "Username"; setText("admin") }
        val inputPass = EditText(this).apply { hint = "Password"; setText("bruce") }
        
        layout.addView(inputUrl)
        layout.addView(inputUser)
        layout.addView(inputPass)

        AlertDialog.Builder(this)
            .setTitle("Bruce Remote Connect")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val intent = Intent(this, BruceRemoteActivity::class.java).apply {
                    putExtra("url", inputUrl.text.toString())
                    putExtra("user", inputUser.text.toString())
                    putExtra("pass", inputPass.text.toString())
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}