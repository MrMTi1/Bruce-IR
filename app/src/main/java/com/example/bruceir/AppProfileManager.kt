package com.example.bruceir

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Profil aplikacji wybierany kodem aktywacyjnym.
 *
 * Bramka jest zaprojektowana tak, aby BŁĘDNY kod nie dawał żadnego dostępu:
 *  - kod poprawny  -> profil zostaje ustawiony i zapisany,
 *  - kod nieznany  -> stan aplikacji pozostaje bez zmian (brak fallbacku do FULL),
 *  - limit prób    -> po [MAX_ATTEMPTS] błędach bramka blokuje się na [LOCKOUT_MS].
 *
 * Uwaga: to jest bramka konfiguracyjna/UI (które moduły pokazywać), a nie
 * kryptograficzna autoryzacja - kody są zaszyte w aplikacji.
 */
object AppProfileManager {

    enum class ProfileMode {
        FULL,           // Wszystkie moduły (kod 7777)
        ELEC_ONLY,      // Tylko Elektryka & Pomiary (kod 1111)
        FULL_NO_ELEC,   // Wszystko oprócz Elektryki (kod 2222)
        IR_ONLY         // Tylko Piloty IR (kod 3333)
    }

    /** Grupy modułów używane do wyliczania widoczności i bramkowania ekranów. */
    enum class ModuleGroup {
        CORE,        // ustawienia, profil, pliki aplikacji - zawsze dostępne
        IR,          // biblioteka IR, import Flipper, live capture IR
        WIRELESS,    // BLE, RF, radar, spectrum, multi-blast
        NETWORK,     // Wi-Fi survey, agresywny skan Wi-Fi, skaner LAN, PCAP
        MACROS,      // makra i automatyka
        ELECTRICIAN, // elektryka i pomiary
        BRUCE        // konsola/kontrola urządzenia Bruce, Web UI, transfer plików firmware
    }

    sealed class CodeResult {
        data class Granted(val mode: ProfileMode) : CodeResult()
        data class Invalid(val attemptsRemaining: Int) : CodeResult()
        data class Locked(val secondsRemaining: Long) : CodeResult()
    }

    private const val PREF_NAME = "app_profile_prefs"
    private const val KEY_MODE = "saved_profile_mode"
    private const val KEY_CONFIGURED = "is_profile_configured"
    private const val KEY_FAILED_ATTEMPTS = "failed_code_attempts"
    private const val KEY_LOCK_UNTIL = "lock_until_elapsed"

    private const val MAX_ATTEMPTS = 5
    private const val LOCKOUT_MS = 60_000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIGURED, false)

    fun getProfileMode(context: Context): ProfileMode {
        val savedName = prefs(context).getString(KEY_MODE, ProfileMode.FULL.name) ?: ProfileMode.FULL.name
        return try {
            ProfileMode.valueOf(savedName)
        } catch (_: Exception) {
            ProfileMode.FULL
        }
    }

    /** Ustawia profil wprost (bez weryfikacji kodu) - używane tylko wewnętrznie. */
    private fun setProfileMode(context: Context, mode: ProfileMode) {
        prefs(context).edit()
            .putString(KEY_MODE, mode.name)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
    }

    /** Kody aktywacyjne. Zwraca null dla kodu nieznanego - NIGDY nie ma fallbacku do FULL. */
    internal fun modeForCode(code: String): ProfileMode? = when (normalize(code)) {
        "1111", "ELEC" -> ProfileMode.ELEC_ONLY
        "2222", "NOELEC" -> ProfileMode.FULL_NO_ELEC
        "3333", "IR" -> ProfileMode.IR_ONLY
        "7777", "FULL", "ADMIN" -> ProfileMode.FULL
        else -> null
    }

    private fun normalize(input: String): String =
        input.trim().replace(" ", "").replace("-", "").uppercase(Locale.US)

    fun lockSecondsRemaining(context: Context): Long {
        val until = prefs(context).getLong(KEY_LOCK_UNTIL, 0L)
        if (until <= 0L) return 0L
        val remaining = until - SystemClock.elapsedRealtime()
        return if (remaining > 0L) (remaining + 999L) / 1000L else 0L
    }

    fun attemptsRemaining(context: Context): Int {
        val failed = prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0)
        return (MAX_ATTEMPTS - failed).coerceAtLeast(0)
    }

    /**
     * Weryfikuje kod i - tylko gdy poprawny - ustawia profil.
     * Błędny kod nie zmienia zapisanego profilu ani flagi konfiguracji.
     */
    fun applyCode(context: Context, codeInput: String): CodeResult {
        val manager = prefs(context)
        val lockLeft = lockSecondsRemaining(context)
        if (lockLeft > 0L) return CodeResult.Locked(lockLeft)

        val mode = modeForCode(codeInput)
        if (mode == null) {
            val failed = manager.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            if (failed >= MAX_ATTEMPTS) {
                manager.edit()
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCK_UNTIL, SystemClock.elapsedRealtime() + LOCKOUT_MS)
                    .apply()
                return CodeResult.Locked(LOCKOUT_MS / 1000L)
            }
            manager.edit().putInt(KEY_FAILED_ATTEMPTS, failed).apply()
            return CodeResult.Invalid((MAX_ATTEMPTS - failed).coerceAtLeast(0))
        }

        manager.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCK_UNTIL)
            .apply()
        setProfileMode(context, mode)
        return CodeResult.Granted(mode)
    }

    /** Czyści profil i licznik prób (powrót do stanu "skonfiguruj przy pierwszym wejściu"). */
    fun resetProfile(context: Context) {
        prefs(context).edit()
            .remove(KEY_MODE)
            .remove(KEY_CONFIGURED)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCK_UNTIL)
            .apply()
    }

    /**
     * Mapowanie profilu na dostępne grupy modułów.
     *
     * 1111 (elektryka) nie pokazuje modułów Bruce (konsola/Web UI/transfer firmware)
     * ani narzędzi radiowych - tylko elektrykę i ustawienia aplikacji.
     */
    fun isGroupAllowed(mode: ProfileMode, group: ModuleGroup): Boolean = when (mode) {
        ProfileMode.FULL -> true
        ProfileMode.FULL_NO_ELEC -> group != ModuleGroup.ELECTRICIAN
        ProfileMode.ELEC_ONLY -> group == ModuleGroup.ELECTRICIAN || group == ModuleGroup.CORE
        ProfileMode.IR_ONLY -> group == ModuleGroup.IR ||
            group == ModuleGroup.CORE ||
            group == ModuleGroup.BRUCE
    }

    /**
     * Dostęp do modułu w bieżącym profilu.
     * Brak skonfigurowanego profilu = pełny dostęp (stan fabryczny aplikacji).
     */
    fun isModuleAvailable(context: Context, group: ModuleGroup): Boolean {
        if (group == ModuleGroup.CORE) return true
        if (!isConfigured(context)) return true
        return isGroupAllowed(getProfileMode(context), group)
    }

    fun profileLabel(mode: ProfileMode): String = when (mode) {
        ProfileMode.FULL -> "Pełny dostęp"
        ProfileMode.FULL_NO_ELEC -> "Wszystko oprócz elektryki"
        ProfileMode.ELEC_ONLY -> "Tylko elektryka i pomiary"
        ProfileMode.IR_ONLY -> "Tylko piloty IR"
    }

    /**
     * Bramka dla ekranów modułów: kończy aktywność i pokazuje komunikat,
     * gdy moduł jest niedostępny w aktywnym profilu. Zwraca true gdy można wejść.
     */
    fun guardModule(activity: AppCompatActivity, group: ModuleGroup): Boolean {
        if (isModuleAvailable(activity, group)) return true
        val mode = getProfileMode(activity)
        Toast.makeText(
            activity,
            "Moduł niedostępny w profilu: ${profileLabel(mode)}. Zmień profil kodem w Warsztacie.",
            Toast.LENGTH_LONG
        ).show()
        activity.finish()
        return false
    }
}
