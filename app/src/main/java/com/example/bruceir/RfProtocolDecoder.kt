package com.example.bruceir

import java.util.Locale

object RfProtocolDecoder {

    data class RfDecodeResult(
        val protocolName: String,
        val frequencyHz: Long,
        val bitCount: Int,
        val hexPayload: String,
        val addressHex: String,
        val commandHex: String,
        val pulseWidthUs: Int,
        val rawPulses: IntArray
    )

    fun decodeRawPulses(frequencyHz: Long, pulses: IntArray): RfDecodeResult {
        if (pulses.size < 10) {
            return RfDecodeResult("UNKNOWN_RF", frequencyHz, 0, "N/A", "0x0", "0x0", 0, pulses)
        }

        val sortedPositives = pulses.map { Math.abs(it) }.filter { it in 100..2000 }.sorted()
        val estimatedPulseWidth = sortedPositives.getOrNull(sortedPositives.size / 4) ?: 350

        val bitList = mutableListOf<Int>()
        var i = 0
        while (i < pulses.size - 1) {
            val p1 = Math.abs(pulses[i])
            if (p1 > estimatedPulseWidth * 2.2f) {
                bitList.add(1)
            } else if (p1 > 0) {
                bitList.add(0)
            }
            i += 2
        }

        val bitCount = bitList.size
        val hexSb = StringBuilder()
        var currentByte = 0
        var bitInByte = 0

        bitList.forEach { bit ->
            currentByte = (currentByte shl 1) or bit
            bitInByte++
            if (bitInByte == 8) {
                hexSb.append("%02X ".format(currentByte))
                currentByte = 0
                bitInByte = 0
            }
        }
        if (bitInByte > 0) {
            hexSb.append("%02X".format(currentByte shl (8 - bitInByte)))
        }

        val rawHex = hexSb.toString().trim()

        val (protocol, addr, cmd) = when {
            bitCount == 24 -> Triple("EV1527 / PT2262", rawHex.take(4), rawHex.drop(4))
            bitCount == 12 -> Triple("CAME TOP / NICE FLO", rawHex.take(2), rawHex.drop(2))
            bitCount in 64..66 -> Triple("KeeLoq Rolling Code", rawHex.take(8), rawHex.drop(8))
            else -> Triple("RAW PWM (Obwód Zewnętrzny)", rawHex, "0x0")
        }

        return RfDecodeResult(
            protocolName = protocol,
            frequencyHz = frequencyHz,
            bitCount = bitCount,
            hexPayload = if (rawHex.isEmpty()) "0x00" else rawHex,
            addressHex = addr,
            commandHex = cmd,
            pulseWidthUs = estimatedPulseWidth,
            rawPulses = pulses
        )
    }

    fun predictNextRollingCode(result1: RfDecodeResult, result2: RfDecodeResult): String {
        if (result1.protocolName.contains("KeeLoq", true) || result2.protocolName.contains("KeeLoq", true)) {
            val num1 = result1.hexPayload.replace(" ", "").toLongOrNull(16) ?: 0L
            val num2 = result2.hexPayload.replace(" ", "").toLongOrNull(16) ?: 0L
            val delta = num2 - num1
            val nextPredicted = num2 + delta
            return "KeeLoq Predictor: %016X (Skok: +%d)".format(Locale.getDefault(), nextPredicted, delta)
        }
        return "Brak zmiennego kodu lub stały adres."
    }
}
