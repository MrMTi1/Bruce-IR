package com.example.bruceir

import java.util.Locale

object KeeloqRemoteGenerator {

    data class RemoteProfile(
        val name: String,
        val serialIdHex: String,
        var counter: Int,
        val frequencyHz: Long,
        val protocol: String
    )

    fun createNewRemote(name: String, frequencyHz: Long = 433920000L): RemoteProfile {
        val randomSerial = (1000000..9999999).random()
        val serialHex = "%07X".format(randomSerial)
        return RemoteProfile(
            name = name,
            serialIdHex = serialHex,
            counter = 1,
            frequencyHz = frequencyHz,
            protocol = "Keeloq / EV1527"
        )
    }

    fun generateTransmissionFrame(profile: RemoteProfile, buttonCode: Int): String {
        profile.counter += 1
        val counterHex = "%04X".format(profile.counter)
        val btnHex = "%01X".format(buttonCode and 0x0F)
        return "${profile.serialIdHex}$counterHex$btnHex"
    }

    fun generatePairingFrame(profile: RemoteProfile): String {
        return "${profile.serialIdHex}00001"
    }
}
