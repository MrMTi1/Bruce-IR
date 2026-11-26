package com.example.bruceir

object IrProtocolEncoder {

    fun encodeToRawPattern(protocol: String, addressHex: String, commandHex: String): IntArray {
        val addr = parseHex(addressHex)
        val cmd = parseHex(commandHex)

        return when (protocol.uppercase()) {
            "NEC", "NECEXT" -> encodeNec(addr, cmd)
            "SAMSUNG32", "SAMSUNG" -> encodeSamsung32(addr, cmd)
            "SIRC", "SONY", "SONY12" -> encodeSonySirc(addr, cmd, 12)
            "SONY15" -> encodeSonySirc(addr, cmd, 15)
            "SONY20" -> encodeSonySirc(addr, cmd, 20)
            "RC5" -> encodeRc5(addr, cmd)
            else -> encodeNec(addr, cmd)
        }
    }

    private fun parseHex(hexStr: String?): Int {
        if (hexStr.isNullOrBlank()) return 0
        val clean = hexStr.removePrefix("0x").removePrefix("HEX:").trim().split(" ").firstOrNull() ?: "0"
        return clean.toIntOrNull(16) ?: 0
    }

    private fun encodeNec(address: Int, command: Int): IntArray {
        val list = mutableListOf<Int>()
        list.add(9000); list.add(4500)

        val addrBits = (address and 0xFF) or (((address and 0xFF).inv() and 0xFF) shl 8)
        val cmdBits = (command and 0xFF) or (((command and 0xFF).inv() and 0xFF) shl 8)
        val fullData = (cmdBits.toLong() shl 16) or addrBits.toLong()

        for (i in 0 until 32) {
            val bit = (fullData shr i) and 1L
            list.add(560)
            list.add(if (bit == 1L) 1690 else 560)
        }
        list.add(560)
        return list.toIntArray()
    }

    private fun encodeSamsung32(address: Int, command: Int): IntArray {
        val list = mutableListOf<Int>()
        list.add(4500); list.add(4500)
        val addrByte = address and 0xFF
        val cmdByte = command and 0xFF
        val invCmdByte = cmdByte.inv() and 0xFF
        val fullData = (invCmdByte shl 24) or (cmdByte shl 16) or (addrByte shl 8) or addrByte

        for (i in 0 until 32) {
            val bit = (fullData shr i) and 1
            list.add(560)
            list.add(if (bit == 1) 1690 else 560)
        }
        list.add(560)
        return list.toIntArray()
    }

    private fun encodeSonySirc(address: Int, command: Int, numBits: Int): IntArray {
        val list = mutableListOf<Int>()
        list.add(2400); list.add(600)
        val fullData = ((address and 0xFF) shl 7) or (command and 0x7F)

        for (i in 0 until numBits) {
            val bit = (fullData shr i) and 1
            list.add(if (bit == 1) 1200 else 600)
            list.add(600)
        }
        return list.toIntArray()
    }

    private fun encodeRc5(address: Int, command: Int): IntArray {
        val list = mutableListOf<Int>()
        list.add(889); list.add(889)
        val fullData = ((address and 0x1F) shl 6) or (command and 0x3F)

        for (i in 11 downTo 0) {
            val bit = (fullData shr i) and 1
            if (bit == 1) {
                list.add(889); list.add(889)
            } else {
                list.add(889); list.add(889)
            }
        }
        return list.toIntArray()
    }
}
