package com.example.bruceir

import android.content.Context
import android.os.Handler
import android.os.Looper

class AcGoneManager(context: Context) {

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var customAttackList: List<IrCommand>? = null
    private val transmitter = IrTransmitter(context)

    fun setAttackList(list: List<IrCommand>) {
        this.customAttackList = list
    }

    fun start(onProgress: (Int, Int, String) -> Unit, onFinished: () -> Unit) {
        if (isRunning) return
        isRunning = true
        
        val listToUse = if (!customAttackList.isNullOrEmpty()) customAttackList!! else AcCodes.goneOff
        val delay = 500L

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
