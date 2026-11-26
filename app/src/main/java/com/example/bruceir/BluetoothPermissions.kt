package com.example.bruceir

/**
 * Bluetooth Permissions - Zarządzanie uprawnieniami Bluetooth
 */
object BluetoothPermissions {

    fun requiresLegacyPermission(): Boolean = true

    fun requiresScanningPermission(): Boolean = false

    fun requiresConnectingPermission(): Boolean = true

    fun requiresAdvertisingPermission(): Boolean = true
}
