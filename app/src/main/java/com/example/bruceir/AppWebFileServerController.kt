package com.example.bruceir

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppWebFileServerController {
    const val PORT = 8080
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: AppWebFileServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        server = AppWebFileServer(context.applicationContext, PORT).also { it.start(scope) }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean = server != null

    /** Token bieżącej sesji huba (pusty, gdy serwer nie działa). */
    fun accessToken(): String = server?.accessToken.orEmpty()

    /** Pełny, tokenowany URL do otwarcia w przeglądarce na komputerze. */
    fun accessUrl(host: String, port: Int = PORT): String? {
        val active = server ?: return null
        return "http://$host:$port/?token=${active.accessToken}"
    }
}
