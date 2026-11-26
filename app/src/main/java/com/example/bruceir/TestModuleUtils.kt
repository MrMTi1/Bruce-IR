package com.example.bruceir

/**
 * Test Module - Moduł testowy BruceIR
 */
object TestModuleUtils {

    fun isDeviceReady(): Boolean = true

    fun getDeviceInfo(): String = "BruceIR Test Module v1.0"

    fun runIrTest(): Result<Pair<Boolean, String>> = Result.success(Pair(true, "OK"))

    fun validateIrCode(code: String?): Boolean = code != null && code.isNotEmpty()

    fun generateTestHash(code: String): String = "${code}.test.hash"
}
