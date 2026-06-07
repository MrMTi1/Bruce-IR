package com.example.bruceir

import android.hardware.ConsumerIrManager
import android.os.Handler
import android.os.Looper

class AcTurboManager(private val irManager: ConsumerIrManager?) {

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    fun start(onProgress: (Int, Int, String) -> Unit, onFinished: () -> Unit) {
        if (isRunning) return
        isRunning = true
        
        val codes = AcCodes.goneTurbo

        Thread {
            for (i in codes.indices) {
                if (!isRunning) break
                val cmd = codes[i]
                handler.post { onProgress(i + 1, codes.size, cmd.name) }
                try {
                    irManager?.transmit(cmd.frequency, cmd.pattern)
                } catch (e: Exception) {}
                Thread.sleep(150) // TURBO: krótszy czas oczekiwania
            }
            isRunning = false
            handler.post { onFinished() }
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}
