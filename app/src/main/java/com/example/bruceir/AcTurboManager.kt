package com.example.bruceir

import android.content.Context
import android.os.Handler
import android.os.Looper

class AcTurboManager(context: Context) {

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val transmitter = IrTransmitter(context)

    fun start(onProgress: (Int, Int, String) -> Unit, onFinished: () -> Unit) {
        if (isRunning) return
        isRunning = true
        
        val listToUse = AcCodes.goneTurbo
        val delay = 150L

        Thread {
            for (i in listToUse.indices) {
                if (!isRunning) break
                val cmd = listToUse[i]
                handler.post { onProgress(i + 1, listToUse.size, cmd.name) }
                try {
                    transmitter.transmit(cmd.frequency, cmd.pattern)
                } catch (e: Exception) {}
                Thread.sleep(delay)
            }
            isRunning = false
            handler.post { onFinished() }
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}
