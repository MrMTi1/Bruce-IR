package com.example.bruceir

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure helpers for presenting Android Wi-Fi scan signal data.
 *
 * These are estimates for user feedback; distance is not a reliable measurement indoors.
 */
internal object WifiSignalUtils {
    fun estimatedDistanceMeters(rssiDbm: Int, frequencyMhz: Int): String {
        if (rssiDbm >= 0) return "? m"
        val frequency = if (frequencyMhz <= 0) 2412 else frequencyMhz
        val exponent = (27.55 - (20 * log10(frequency.toDouble())) + abs(rssiDbm)) / 20.0
        val meters = 10.0.pow(exponent)
        return when {
            meters < 10.0 -> String.format(Locale.US, "%.1f m", meters)
            meters < 100.0 -> String.format(Locale.US, "%.0f m", meters)
            else -> ">100 m"
        }
    }

    fun signalPercent(rssiDbm: Int): Int = when {
        rssiDbm >= -50 -> 100
        rssiDbm <= -100 -> 0
        else -> 2 * (rssiDbm + 100)
    }.coerceIn(0, 100)

    fun qualityText(percentage: Int): String = when {
        percentage >= 80 -> "Excellent 🟢"
        percentage >= 60 -> "Good 🟡"
        percentage >= 40 -> "Fair 🟧"
        percentage >= 20 -> "Weak 🔴"
        else -> "Very Poor ⚪"
    }

    fun signalGauge(percentage: Int): String {
        val boundedPercentage = percentage.coerceIn(0, 100)
        val filled = boundedPercentage / 10
        val bar = "█".repeat(filled) + "░".repeat(10 - filled)
        return "$boundedPercentage% [$bar]"
    }

    fun channel(frequencyMhz: Int): String = when (frequencyMhz) {
        in 2412..2472 -> ((frequencyMhz - 2407) / 5).toString()
        2484 -> "14"
        in 5035..5895 -> ((frequencyMhz - 5000) / 5).toString()
        in 5955..7115 -> ((frequencyMhz - 5950) / 5).toString()
        else -> "?"
    }
}
