package com.example.bruceir

import android.util.Log

object BruceBleRadioBridge {

    private const val TAG = "BruceBleRadioBridge"

    interface RadioBridgeListener {
        fun onRfSignalReceived(result: RfProtocolDecoder.RfDecodeResult)
        fun onStatusChanged(statusText: String)
    }

    private var activeListener: RadioBridgeListener? = null

    fun setListener(listener: RadioBridgeListener?) {
        activeListener = listener
    }

    fun startListeningRf(frequencyHz: Long) {
        if (!BruceBleManager.isConnected) {
            activeListener?.onStatusChanged("❌ BRAK POŁĄCZENIA Z LILYGO/BRUCE! Włącz Bluetooth i połącz się najpierw w Skanerze BLE!")
            return
        }
        val rxCmd = "subghz rx raw 15"
        val sent = BruceBleManager.sendCommand(rxCmd)
        if (sent) {
            activeListener?.onStatusChanged("🟢 Aktywny nasłuch sprzętowy Bruce RF (${frequencyHz / 1000000.0} MHz)...")
        } else {
            activeListener?.onStatusChanged("❌ Błąd wysłania komendy do Bruce!")
        }
    }

    fun stopListeningRf() {
        if (BruceBleManager.isConnected) {
            BruceBleManager.sendCommand("power reboot")
        }
        activeListener?.onStatusChanged("Zatrzymano nasłuch radiowy.")
    }

    fun transmitRfPayload(frequencyHz: Long, hexPayload: String): Boolean {
        if (!BruceBleManager.isConnected) {
            activeListener?.onStatusChanged("❌ BRAK POŁĄCZENIA BLE z LilyGO/Bruce! Nie można nadać sygnału.")
            return false
        }
        val cleanHex = hexPayload.replace(" ", "").removePrefix("0x")
        val cmd = "subghz txp EV1527 $frequencyHz 24 0x$cleanHex 350 10"
        val sent = BruceBleManager.sendCommand(cmd)
        if (sent) {
            activeListener?.onStatusChanged("🟢 Wysłano sygnał RF do Bruce ($frequencyHz Hz): $cleanHex")
        } else {
            activeListener?.onStatusChanged("❌ Błąd transmisji przez BLE!")
        }
        return sent
    }

    fun processIncomingBleData(frequencyHz: Long, rawText: String, bytes: ByteArray) {
        val cleanText = rawText.trim()
        val pulseInts = mutableListOf<Int>()

        if (cleanText.contains(" ") || cleanText.contains("\n")) {
            cleanText.split(Regex("[\\s,]+")).forEach { token ->
                token.trim().toIntOrNull()?.let { pulseInts.add(it) }
            }
        }

        val pulses = if (pulseInts.size >= 8) pulseInts.toIntArray() else bytes.map { it.toInt() }.toIntArray()
        if (pulses.size >= 8) {
            val decoded = RfProtocolDecoder.decodeRawPulses(frequencyHz, pulses)
            activeListener?.onRfSignalReceived(decoded)
        }
    }
}
