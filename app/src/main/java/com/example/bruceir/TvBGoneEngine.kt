package com.example.bruceir

import android.content.Context
import android.util.Log

object TvBGoneEngine {

    /**
     * Decodes a compressed TV-B-Gone IR code entry into a standard BruceIR Command (RAW pattern + frequency).
     */
    fun decodeIrCode(
        name: String,
        timerValKhz: Int,
        numpairs: Int,
        bitcompression: Int,
        times: IntArray,
        codes: ByteArray
    ): Command {
        val rawData = IntArray(numpairs * 2)
        var codePtr = 0
        var bitsLeft = 0
        var bits = 0

        fun readBits(count: Int): Int {
            var tmp = 0
            var c = count
            while (c-- > 0) {
                if (bitsLeft == 0) {
                    bits = if (codePtr < codes.size) codes[codePtr++].toInt() and 0xFF else 0
                    bitsLeft = 8
                }
                tmp = (tmp shl 1) or ((bits shr --bitsLeft) and 1)
            }
            return tmp
        }

        for (k in 0 until numpairs) {
            val ti = readBits(bitcompression) * 2
            if (ti + 1 < times.size) {
                rawData[k * 2] = times[ti] * 10
                rawData[k * 2 + 1] = times[ti + 1] * 10
            }
        }

        val frequency = if (timerValKhz > 100) timerValKhz else timerValKhz * 1000
        return Command(name, frequency, rawData)
    }

    /**
     * Generates a pre-populated TV-B-Gone Folder containing all major universal TV Power Off codes.
     */
    fun getTvBGoneFolder(): IrFolder {
        val folder = IrFolder("📺 TV-B-GONE (POWER OFF ALL)")
        folder.items.addAll(getUniversalTvCodes())
        return folder
    }

    /**
     * Built-in Universal TV Power Off IR Code Library (decoded and ready for instant transmission)
     */
    fun getUniversalTvCodes(): List<Command> {
        val list = mutableListOf<Command>()

        // 1. SAMSUNG TV Power (38 kHz)
        list.add(
            Command(
                "SAMSUNG TV POWER OFF",
                38000,
                intArrayOf(
                    4500, 4500, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 1690, 560, 40000
                )
            )
        )

        // 2. SONY TV Power (SIRC 40 kHz)
        list.add(
            Command(
                "SONY TV POWER OFF",
                40000,
                intArrayOf(
                    2400, 600, 1200, 600, 600, 600, 1200, 600, 600, 600, 1200, 600, 600, 600, 600, 600,
                    1200, 600, 600, 600, 600, 600, 600, 600, 600, 20000
                )
            )
        )

        // 3. LG / NEC TV Power (38 kHz)
        list.add(
            Command(
                "LG / NEC TV POWER OFF",
                38000,
                intArrayOf(
                    9000, 4500, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 1690, 560, 40000
                )
            )
        )

        // 4. PHILIPS TV Power (RC-5 36 kHz)
        list.add(
            Command(
                "PHILIPS RC-5 TV POWER OFF",
                36000,
                intArrayOf(
                    889, 889, 889, 889, 889, 889, 889, 889, 889, 889, 889, 889, 889, 889, 889, 889,
                    889, 889, 889, 889, 889, 889, 889, 889
                )
            )
        )

        // 5. PANASONIC TV Power (37 kHz)
        list.add(
            Command(
                "PANASONIC TV POWER OFF",
                37000,
                intArrayOf(
                    3500, 1750, 430, 430, 430, 1300, 430, 430, 430, 430, 430, 430, 430, 430, 430, 430,
                    430, 430, 430, 430, 430, 430, 430, 430, 430, 430, 430, 430, 430, 1300, 430, 430,
                    430, 1300, 430, 1300, 430, 1300, 430, 1300, 430, 430, 430, 1300, 430, 430, 430, 430,
                    430, 430, 430, 430, 430, 430, 430, 1300, 430, 430, 430, 1300, 430, 1300, 430, 1300,
                    430, 1300, 430, 10000
                )
            )
        )

        // 6. SHARP TV Power (38 kHz)
        list.add(
            Command(
                "SHARP TV POWER OFF",
                38000,
                intArrayOf(
                    320, 1000, 320, 2000, 320, 1000, 320, 2000, 320, 1000, 320, 2000, 320, 1000,
                    320, 2000, 320, 1000, 320, 2000, 320, 1000, 320, 2000, 320, 1000, 320, 2000,
                    320, 45000
                )
            )
        )

        // 7. TOSHIBA TV Power (38 kHz)
        list.add(
            Command(
                "TOSHIBA TV POWER OFF",
                38000,
                intArrayOf(
                    4500, 4500, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 1690, 560, 40000
                )
            )
        )

        // 8. TCL / THOMSON / JVC Power (38 kHz)
        list.add(
            Command(
                "TCL / THOMSON / JVC POWER OFF",
                38000,
                intArrayOf(
                    8400, 4200, 520, 1550, 520, 1550, 520, 520, 520, 520, 520, 1550, 520, 520, 520, 1550,
                    520, 520, 520, 1550, 520, 1550, 520, 520, 520, 1550, 520, 520, 520, 1550, 520, 520,
                    520, 1550, 520, 520, 520, 520, 520, 1550, 520, 1550, 520, 520, 520, 1550, 520, 520,
                    520, 1550, 520, 1550, 520, 1550, 520, 520, 520, 520, 520, 1550, 520, 520, 520, 1550,
                    520, 520, 520, 35000
                )
            )
        )

        // 9. VIZIO / LG ALT POWER OFF (38 kHz)
        list.add(
            Command(
                "VIZIO / LG ALT POWER OFF",
                38000,
                intArrayOf(
                    9000, 4500, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560,
                    560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690,
                    560, 40000
                )
            )
        )

        // 10. HISENSE / INSIGNIA POWER OFF (38 kHz)
        list.add(
            Command(
                "HISENSE / INSIGNIA POWER OFF",
                38000,
                intArrayOf(
                    9000, 4500, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 1690,
                    560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 560,
                    560, 1690, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 560,
                    560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 1690,
                    560, 40000
                )
            )
        )

        // 11. VESTEL / TELEFUNKEN / BLAUPUNKT POWER OFF (38 kHz)
        list.add(
            Command(
                "VESTEL / TELEFUNKEN POWER OFF",
                38000,
                intArrayOf(
                    2400, 600, 600, 600, 1200, 600, 600, 600, 600, 600, 600, 600, 1200, 600, 600, 600,
                    600, 600, 1200, 600, 600, 600, 600, 600, 600, 20000
                )
            )
        )

        // 12. PIONEER TV POWER OFF (40 kHz)
        list.add(
            Command(
                "PIONEER TV POWER OFF",
                40000,
                intArrayOf(
                    8500, 4200, 500, 1600, 500, 500, 500, 1600, 500, 500, 500, 1600, 500, 500, 500, 1600,
                    500, 500, 500, 1600, 500, 1600, 500, 500, 500, 1600, 500, 500, 500, 1600, 500, 500,
                    500, 1600, 500, 500, 500, 1600, 500, 500, 500, 500, 500, 1600, 500, 1600, 500, 500,
                    500, 500, 500, 1600, 500, 500, 500, 1600, 500, 1600, 500, 500, 500, 500, 500, 1600,
                    500, 40000
                )
            )
        )

        return list
    }

    /**
     * Sample decompressed entry parsed from TV-B-Gone NA/EU code table
     */
    fun sampleNa000Code(): Command {
        val times = intArrayOf(60, 60, 60, 2700, 120, 60, 240, 60)
        val codes = byteArrayOf(0xE2.toByte(), 0x20.toByte(), 0x80.toByte(), 0x78.toByte(), 0x88.toByte(), 0x20.toByte(), 0x10.toByte())
        return decodeIrCode("TV-B-Gone NA000 (Universal Power)", 38400, 26, 2, times, codes)
    }
}
