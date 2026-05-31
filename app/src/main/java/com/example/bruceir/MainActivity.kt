package com.example.bruceir

import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.text.InputType
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private var irManager: ConsumerIrManager? = null
    private lateinit var adapter: CommandAdapter
    private var allData = IrFolder("ROOT") 
    private var currentFolder = allData    
    private val gson = Gson()
    private var isEditMode = false 

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importBruceFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Czysty pasek statusu
        try {
            window.statusBarColor = Color.WHITE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
            }
        } catch (e: Exception) { e.printStackTrace() }

        irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        load()

        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        val lm = GridLayoutManager(this, 3)
        
        adapter = CommandAdapter(currentFolder.items, irManager) { action, item, pos ->
            when (action) {
                CommandAdapter.ActionType.DELETE -> {
                    currentFolder.items.removeAt(pos)
                    save(); adapter.notifyDataSetChanged()
                }
                CommandAdapter.ActionType.EDIT -> {
                    if (item is Command) showEditCmd(item, pos)
                    else if (item is IrFolder) showRenameFolder(item, pos)
                }
                CommandAdapter.ActionType.OPEN -> {
                    if (item is IrFolder) {
                        currentFolder = item
                        refreshList()
                    }
                }
            }
        }

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
            }
        })
        touchHelper.attachToRecyclerView(rv)

        val btnLock = findViewById<ImageButton>(R.id.btnLock)
        btnLock.setOnClickListener {
            isEditMode = !isEditMode
            adapter.setEditMode(isEditMode)
            btnLock.setImageResource(if (isEditMode) android.R.drawable.ic_partial_secure else android.R.drawable.ic_secure)
            Toast.makeText(this, if (isEditMode) "Tryb Edycji: Aktywny" else "Tryb Edycji: Zablokowany", Toast.LENGTH_SHORT).show()
        }

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showAddMenu() }
        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener { showInfoDialog() }

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

    private fun refreshList() {
        adapter.updateList(currentFolder.items)
        findViewById<TextView>(R.id.tvHeaderTitle).text = if (currentFolder == allData) "BruceIr By MTi" else currentFolder.name
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("O aplikacji")
            .setMessage("BruceIr By MTi\n\nProfesjonalny manager sygnałów IR.\n\nStworzony przez MTi do obsługi plików .ir pochodzących z Flipper/Bruce Firmware.\n\nWspiera Drag & Drop, kłódkę bezpieczeństwa oraz surowe dane RAW.")
            .setPositiveButton("OK", null).show()
    }

    private fun importBruceFile(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val content = r.readText()
                val imported = mutableListOf<Any>()
                var name = ""; var freq = 38000
                content.lines().forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("name:")) name = t.substringAfter(":").trim()
                    if (t.startsWith("frequency:")) freq = t.substringAfter(":").trim().toIntOrNull() ?: 38000
                    if (t.startsWith("data:")) {
                        val raw = t.substringAfter(":").trim().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray()
                        if (name.isNotEmpty() && raw.isNotEmpty()) {
                            imported.add(Command(name, freq, if (raw[0] == 0) raw.drop(1).toIntArray() else raw))
                        }
                    }
                }
                if (imported.isNotEmpty()) {
                    val fName = uri.lastPathSegment?.substringAfterLast("/")?.uppercase() ?: "IMPORT"
                    currentFolder.items.add(IrFolder(fName, imported))
                    save(); refreshList()
                }
            }
        } catch (e: Exception) { Toast.makeText(this, "Błąd importu", Toast.LENGTH_SHORT).show() }
    }

    private fun showAddMenu() {
        val ops = arrayOf("Wczytaj plik .ir", "Nowy Folder", "Przycisk Ręcznie")
        AlertDialog.Builder(this).setItems(ops) { _, w ->
            when (w) {
                0 -> pickFile.launch("*/*")
                1 -> showAddFolder()
                2 -> showAddManual()
            }
        }.show()
    }

    private fun showAddFolder() {
        val input = EditText(this).apply { hint = "Nazwa" }
        AlertDialog.Builder(this).setTitle("Nowy Folder").setView(input).setPositiveButton("OK") { _, _ ->
            currentFolder.items.add(IrFolder(input.text.toString().uppercase()))
            save(); refreshList()
        }.show()
    }

    private fun showAddManual() {
        val nameIn = EditText(this).apply { hint = "Nazwa" }
        AlertDialog.Builder(this).setTitle("Szybki Przycisk").setView(nameIn).setPositiveButton("OK") { _, _ ->
            currentFolder.items.add(Command(nameIn.text.toString(), 38000, intArrayOf(100, 100)))
            save(); refreshList()
        }.show()
    }

    private fun showEditCmd(cmd: Command, pos: Int) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
        val nameIn = EditText(this).apply { setText(cmd.name) }
        val dataIn = EditText(this).apply { setText(cmd.pattern.joinToString(" ")) }
        lay.addView(TextView(this).apply { text = "Nazwa:" }); lay.addView(nameIn)
        lay.addView(TextView(this).apply { text = "Kody RAW:" }); lay.addView(dataIn)
        AlertDialog.Builder(this).setTitle("Edytuj").setView(lay).setPositiveButton("Zapisz") { _, _ ->
            try {
                cmd.name = nameIn.text.toString()
                cmd.pattern = dataIn.text.toString().split(" ").filter { it.isNotEmpty() }.map { abs(it.trim().toInt()) }.toIntArray()
                save(); adapter.notifyItemChanged(pos)
            } catch (e: Exception) {}
        }.setNegativeButton("Usuń") { _, _ ->
            currentFolder.items.removeAt(pos); save(); adapter.notifyDataSetChanged()
        }.show()
    }

    private fun showRenameFolder(f: IrFolder, pos: Int) {
        val input = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this).setTitle("Zmień nazwę").setView(input).setPositiveButton("OK") { _, _ ->
            f.name = input.text.toString().uppercase(); save(); adapter.notifyItemChanged(pos)
        }.setNegativeButton("Usuń") { _, _ ->
            currentFolder.items.removeAt(pos); save(); adapter.notifyDataSetChanged()
        }.show()
    }

    private fun save() {
        val json = gson.toJson(allData)
        getSharedPreferences("ir_prefs", MODE_PRIVATE).edit().putString("full_data_v5", json).apply()
    }

    private fun load() {
        val json = getSharedPreferences("ir_prefs", MODE_PRIVATE).getString("full_data_v5", null) ?: return
        try {
            val rootMap = gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
            allData = parseFolder(rootMap)
            currentFolder = allData
        } catch (e: Exception) {}
    }

    private fun parseFolder(map: Map<String, Any>): IrFolder {
        val folder = IrFolder(map["name"] as String)
        val itemsRaw = map["items"] as? List<Map<String, Any>>
        itemsRaw?.forEach { itemMap ->
            if (itemMap["type"] == "folder") folder.items.add(parseFolder(itemMap))
            else {
                val cmd = Command(
                    itemMap["name"] as String,
                    (itemMap["frequency"] as Double).toInt(),
                    (itemMap["pattern"] as List<Double>).map { it.toInt() }.toIntArray()
                )
                folder.items.add(cmd)
            }
        }
        return folder
    }
}