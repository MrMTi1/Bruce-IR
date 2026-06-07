package com.example.bruceir

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MacroManager(private val context: Context, private val transmitter: IrTransmitter) {

    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var isRunning = false

    fun getAllMacros(): MutableList<MacroSet> {
        val prefs = context.getSharedPreferences("macro_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("all_macros", null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<MacroSet>>() {}.type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAllMacros(list: List<MacroSet>) {
        val json = gson.toJson(list)
        context.getSharedPreferences("macro_prefs", Context.MODE_PRIVATE).edit()
            .putString("all_macros", json).apply()
    }

    fun getMacro(name: String): MacroSet {
        val all = getAllMacros()
        return all.find { it.name == name } ?: MacroSet(name).also {
            all.add(it)
            saveAllMacros(all)
        }
    }

    fun updateMacro(macro: MacroSet) {
        val all = getAllMacros()
        val index = all.indexOfFirst { it.name == macro.name }
        if (index != -1) {
            all[index] = macro
        } else {
            all.add(macro)
        }
        saveAllMacros(all)
    }

    fun start(macro: MacroSet, onProgress: (Int, Int, String) -> Unit, onCommandSent: (Command) -> Unit, onFinished: () -> Unit) {
        if (isRunning) return
        if (macro.commands.isEmpty()) {
            onFinished()
            return
        }

        isRunning = true
        Thread {
            do {
                macro.commands.forEachIndexed { index, cmd ->
                    if (!isRunning) return@forEachIndexed
                    handler.post { onProgress(index + 1, macro.commands.size, cmd.name) }
                    
                    try {
                        transmitter.transmit(cmd.frequency, cmd.pattern)
                        handler.post { onCommandSent(cmd) }
                    } catch (e: Exception) {}
                    
                    if (macro.delayMs > 0) Thread.sleep(macro.delayMs)
                }
            } while (macro.isLooping && isRunning)

            isRunning = false
            handler.post { onFinished() }
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}
