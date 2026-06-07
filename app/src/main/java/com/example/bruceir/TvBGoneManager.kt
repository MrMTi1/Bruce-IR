package com.example.bruceir

import android.content.Context
import android.os.Handler
import android.os.Looper

class TvBGoneManager(context: Context) {

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var customAttackList: MutableList<IrCommand> = mutableListOf()
    private val transmitter = IrTransmitter(context)

    fun setAttackList(list: List<IrCommand>) {
        this.customAttackList = list.toMutableList()
    }

    fun getAttackList(): List<IrCommand> {
        return customAttackList
    }

    fun start(onProgress: (Int, Int, String) -> Unit, onFinished: () -> Unit) {
        if (isRunning) return
        isRunning = true

        val listToUse = if (customAttackList.isNotEmpty()) {
            customAttackList
        } else {
            val combined = mutableListOf<IrCommand>()
            combined.addAll(TvBGoneCodes.getEuCodes())
            combined.addAll(TvBGoneCodes.getNaCodes())
            combined
        }

        val delay = 250L

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
