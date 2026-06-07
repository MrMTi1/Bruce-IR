package com.example.bruceir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MacroManager(private val context: Context, private val irManager: ConsumerIrManager?) {

    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var isRunning = false

    fun getMacro(): MutableList<Command> {
        val prefs = context.getSharedPreferences("macro_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("current_macro", null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<Command>>() {}.type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveMacro(list: List<Command>) {
        val json = gson.toJson(list)
        context.getSharedPreferences("macro_prefs", Context.MODE_PRIVATE).edit()
            .putString("current_macro", json).apply()
    }

    fun start(delayMs: Long = 600, isLooping: Boolean = false, onProgress: (Int, Int, String) -> Unit, onFinished: () -> Unit) {
        if (isRunning) return
        val macro = getMacro()
        if (macro.isEmpty()) {
            onFinished()
            return
        }

        isRunning = true
        Thread {
            do {
                macro.forEachIndexed { index, cmd ->
                    if (!isRunning) return@forEachIndexed
                    handler.post { onProgress(index + 1, macro.size, cmd.name) }
                    
                    try {
                        irManager?.transmit(cmd.frequency, cmd.pattern)
                    } catch (e: Exception) {}
                    
                    if (delayMs > 0) Thread.sleep(delayMs)
                }
            } while (isLooping && isRunning)

            isRunning = false
            handler.post { onFinished() }
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}
