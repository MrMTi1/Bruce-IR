package com.example.bruceir

import android.bluetooth.BluetoothAdapter

/**
 * Bluetooth Utils - Narzędzia do obsługi Bluetooth
 */
object BluetoothUtils {

    fun getAdapter(): BluetoothAdapter? = null

    fun isBluetoothEnabled(): Boolean = false

    fun parseGattService(serviceUuid: String): Pair<String, String> {
        val parts = serviceUuid.split("/")
        return Pair(parts[0], parts[1])
    }

    fun formatGattService(services: List<Pair<String, String>>): String {
        return services.joinToString(", ") { "${it.first}/${it.second}" }
    }
}
