package com.example.bruceir

import java.util.Locale

object BruceRfProtocolLibrary {

    data class RfProtocolSpec(
        val name: String,
        val frequencyHz: Long,
        val bitCount: Int,
        val defaultTeUs: Int,
        val description: String
    )

    val PUBLIC_PROTOCOLS = listOf(
        RfProtocolSpec("CAME TOP", 433920000L, 12, 320, "12-bitowe piloty ze stałym kodem (Bramy CAME)"),
        RfProtocolSpec("NICE FLO", 433920000L, 12, 500, "12-bitowe piloty z przełącznikami DIP (Bramy NICE)"),
        RfProtocolSpec("EV1527 / PT2262", 433920000L, 24, 350, "24-bitowe piloty uczące (Rolety, przekaźniki, oświetlenie)"),
        RfProtocolSpec("Princeton", 433920000L, 24, 400, "24-bitowy protokół radiowy PWM"),
        RfProtocolSpec("HT12E", 315000000L, 12, 450, "12-bitowy protokół radiowy adres/dane 315 MHz"),
        RfProtocolSpec("An-Motors", 433920000L, 24, 420, "24-bitowy protokół do bram automatycznych")
    )

    fun encodeProtocolToPayload(spec: RfProtocolSpec, addressHex: String, commandHex: String): String {
        val cleanAddr = addressHex.replace(" ", "").trim().ifEmpty { "A1" }
        val cleanCmd = commandHex.replace(" ", "").trim().ifEmpty { "01" }
        return "$cleanAddr$cleanCmd"
    }

    fun generateFlipperSubGhzHeader(spec: RfProtocolSpec, hexPayload: String): String {
        return "Filetype: Flipper SubGhz Key File\n" +
                "Version: 1\n" +
                "Frequency: ${spec.frequencyHz}\n" +
                "Preset: FuriHalSubGhzPreset2FSKAsync\n" +
                "Protocol: ${spec.name}\n" +
                "Bit: ${spec.bitCount}\n" +
                "Key: $hexPayload\n" +
                "TE: ${spec.defaultTeUs}\n"
    }
}
