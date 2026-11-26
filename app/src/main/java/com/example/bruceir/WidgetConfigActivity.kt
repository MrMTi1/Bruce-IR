package com.example.bruceir

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val gson = Gson()

    private class WidgetItem(
        val title: String,
        val subtitle: String,
        val type: String, // "header", "macro", "command", "empty"
        val payload: Any?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) 
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val listView = findViewById<ListView>(R.id.lvActions)
        val items = mutableListOf<WidgetItem>()

        // 1. Macros Section Header
        items.add(WidgetItem("⚡ MAKRA I AUTOMATYKA", "Wybierz makro do szybkiego skrótu na pulpicie", "header", null))

        // Load Macros
        val macroManager = MacroManager(this, IrTransmitter(this))
        val allMacros = macroManager.getAllMacros()
        if (allMacros.isEmpty()) {
            items.add(WidgetItem("Brak zdefiniowanych makr", "Utwórz makro w Studio Makr", "empty", null))
        } else {
            allMacros.forEach { macro ->
                items.add(WidgetItem("⚡ ${macro.name}", "${macro.commands.size} komend w sekwencji", "macro", macro))
            }
        }

        // 2. IR Commands Section Header
        items.add(WidgetItem("📡 KODY I PILOTY IR", "Wybierz pojedynczą komendę IR do widżetu", "header", null))

        // Load Commands
        val allData = BruceUtils.loadAllData(this)
        val allCommands = mutableListOf<Command>()
        findAllCommands(allData, allCommands)

        if (allCommands.isEmpty()) {
            items.add(WidgetItem("Brak komend IR", "Zaimportuj lub dodaj piloty w bibliotece", "empty", null))
        } else {
            allCommands.forEach { cmd ->
                items.add(WidgetItem("📡 ${cmd.name}", "${cmd.frequency} Hz", "command", cmd))
            }
        }

        val adapter = object : ArrayAdapter<WidgetItem>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position)!!
                val t1 = view.findViewById<TextView>(android.R.id.text1)
                val t2 = view.findViewById<TextView>(android.R.id.text2)

                t1.text = item.title
                t2.text = item.subtitle

                if (item.type == "header") {
                    t1.setTextColor(Color.parseColor("#43C59E"))
                    t1.textSize = 14f
                    t2.setTextColor(Color.parseColor("#8290A2"))
                    t2.textSize = 11f
                    view.setBackgroundColor(Color.parseColor("#141B25"))
                    view.isEnabled = false
                } else if (item.type == "empty") {
                    t1.setTextColor(Color.parseColor("#9EADBD"))
                    t2.setTextColor(Color.parseColor("#748397"))
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.isEnabled = false
                } else {
                    t1.setTextColor(Color.WHITE)
                    t1.textSize = 15f
                    t2.setTextColor(Color.parseColor("#9EADBD"))
                    t2.textSize = 12f
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.isEnabled = true
                }
                return view
            }

            override fun isEnabled(position: Int): Boolean {
                val item = getItem(position)
                return item?.type == "macro" || item?.type == "command"
            }
        }

        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = items[position]
            if (selected.payload != null) {
                saveWidgetConfig(selected.payload)
            }
        }

        setResult(RESULT_CANCELED)
    }

    private fun findAllCommands(folder: IrFolder, list: MutableList<Command>) {
        folder.items.forEach {
            if (it is Command && it.frequency > 0) list.add(it)
            else if (it is IrFolder) findAllCommands(it, list)
        }
    }

    private fun saveWidgetConfig(item: Any) {
        val prefs = getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
        val label: String
        val type: String
        val data: String

        if (item is Command) {
            label = item.name
            type = "command"
            data = gson.toJson(item)
        } else if (item is MacroSet) {
            label = item.name
            type = "macro"
            data = gson.toJson(item)
        } else return

        prefs.putString("widget_${appWidgetId}_label", label)
        prefs.putString("widget_${appWidgetId}_type", type)
        prefs.putString("widget_${appWidgetId}_data", data)
        prefs.apply()

        val appWidgetManager = AppWidgetManager.getInstance(this)
        BruceWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
