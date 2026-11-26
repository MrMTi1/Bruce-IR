package com.example.bruceir

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class MacroManager(private val context: Context, private val transmitter: IrTransmitter) {

    private val gson = Gson()
    private var isRunning = false
    private var macroJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        macroJob = scope.launch {
            withContext(Dispatchers.IO) {
                do {
                    macro.commands.forEachIndexed { index, cmd ->
                        if (!isRunning) return@forEachIndexed
                        withContext(Dispatchers.Main) { onProgress(index + 1, macro.commands.size, cmd.name) }
                        
                        try {
                            transmitter.transmit(cmd.frequency, cmd.pattern)
                            withContext(Dispatchers.Main) { onCommandSent(cmd) }
                        } catch (e: Exception) {}
                        
                        if (macro.delayMs > 0) delay(macro.delayMs)
                    }
                } while (macro.isLooping && isRunning)
            }
            isRunning = false
            onFinished()
        }
    }

    fun stop() {
        isRunning = false
        macroJob?.cancel()
    }
}
