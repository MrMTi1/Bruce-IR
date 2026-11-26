package com.example.bruceir

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL
import java.net.NetworkInterface

class WorkbenchSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var address: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var profileStatus: TextView
    private var connectionJob: Job? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("theme_sound_enabled", true)) {
            ThemeSoundController.play(this)
        }
        if (::profileStatus.isInitialized) profileStatus.text = profileDescription()
    }

    private fun profileDescription(): String =
        if (AppProfileManager.isConfigured(this)) {
            "Aktywny profil: ${AppProfileManager.profileLabel(AppProfileManager.getProfileMode(this))}"
        } else {
            "Profil nieustawiony - przy pierwszym wejściu do Warsztatu wymagany jest kod."
        }

    private fun showProfileCodeDialog() {
        if (AppProfileManager.lockSecondsRemaining(this) > 0L) {
            android.widget.Toast.makeText(
                this,
                "Zbyt wiele błędnych prób. Odczekaj ${AppProfileManager.lockSecondsRemaining(this)} s.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val input = EditText(this).apply {
            hint = "Kod profilu (zna go właściciel aplikacji)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8290A2.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔑 Zmień Profil Aplikacji")
            .setView(input)
            .setPositiveButton("ZATWIERDŹ", null)
            .setNegativeButton("ANULUJ", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val result = AppProfileManager.applyCode(this, input.text.toString())) {
                    is AppProfileManager.CodeResult.Granted -> {
                        android.widget.Toast.makeText(
                            this,
                            "Profil ustawiony: ${AppProfileManager.profileLabel(result.mode)}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                        recreate()
                    }
                    is AppProfileManager.CodeResult.Invalid -> {
                        input.setText("")
                        input.error = "Nieprawidłowy kod. Pozostałe próby: ${result.attemptsRemaining}."
                    }
                    is AppProfileManager.CodeResult.Locked -> {
                        input.setText("")
                        input.error = "Zbyt wiele błędnych kodów. Spróbuj za ${result.secondsRemaining} s."
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmProfileReset() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset profilu")
            .setMessage(
                "Usunąć zapisany profil i wrócić do bramki kodu? " +
                    "Po resecie Warsztat będzie wymagał ponownego wpisania kodu."
            )
            .setPositiveButton("RESETUJ") { _, _ ->
                AppProfileManager.resetProfile(this)
                android.widget.Toast.makeText(this, "Profil usunięty.", android.widget.Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("ANULUJ", null)
            .show()
    }

    override fun onPause() {
        ThemeSoundController.pause()
        super.onPause()
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        super.onDestroy()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF070A0F.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 24f
            minWidth = dp(48)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(label(getString(R.string.workbench_eyebrow), 10f, 0xFF43C59E.toInt(), true))
        titles.addView(label(getString(R.string.settings_screen_title), 23f, Color.WHITE, true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titles)
        content.addView(header)
        content.addView(label(getString(R.string.settings_screen_intro), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(8), 0, dp(18))
        })

        section(content, getString(R.string.settings_section_connection))
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val connectionCard = card()
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        address = inputField(
            getString(R.string.settings_connection_url),
            prefs.getString("bruce_url", "http://bruce.local").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        fields.addView(label(getString(R.string.settings_connection_url_help), 11f, 0xFF9EADBD.toInt()).apply {
            setPadding(dp(2), dp(4), dp(2), 0)
        })
        fields.addView(address, verticalParams())
        username = inputField(
            getString(R.string.bruce_username),
            prefs.getString("bruce_user", "admin").orEmpty(),
            InputType.TYPE_CLASS_TEXT
        )
        password = inputField(
            getString(R.string.bruce_password),
            prefs.getString("bruce_pass", "bruce").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        fields.addView(username, verticalParams(dp(8)))
        fields.addView(password, verticalParams(dp(8)))
        val test = MaterialButton(this).apply {
            text = getString(R.string.home_connection_test_button)
            setOnClickListener { testConnection() }
        }
        fields.addView(test, verticalParams(dp(10)))
        status = label(getString(R.string.home_connection_not_checked), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        fields.addView(status)
        fields.addView(label(getString(R.string.settings_privacy), 11f, 0xFF8792A2.toInt()).apply {
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        connectionCard.addView(fields)
        content.addView(connectionCard, verticalParams(dp(20)))

        section(content, getString(R.string.settings_section_app))
        addSwitch(
            content,
            R.string.settings_instant_boot,
            R.string.settings_instant_boot_desc,
            "instant_boot",
            false
        )
        addSwitch(
            content,
            R.string.settings_click_sound,
            R.string.settings_click_sound_desc,
            "click_sound",
            true
        )
        addSwitch(
            content,
            R.string.settings_haptic,
            R.string.settings_haptic_desc,
            "haptic_enabled",
            true
        )
        addSwitch(
            content,
            R.string.settings_theme_sound,
            R.string.settings_theme_sound_desc,
            "theme_sound_enabled",
            true
        ) { enabled ->
            if (enabled) ThemeSoundController.play(this) else ThemeSoundController.pause()
        }
        addSwitch(
            content,
            R.string.settings_web_hub,
            R.string.settings_web_hub_desc,
            "web_hub_enabled",
            false
        ) { enabled ->
            if (enabled) {
                AppWebFileServerController.start(this)
                val url = AppWebFileServerController.accessUrl(localIp())
                android.widget.Toast.makeText(
                    this,
                    url?.let { "Web Hub: $it" } ?: getString(R.string.settings_server_started),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                AppWebFileServerController.stop()
                android.widget.Toast.makeText(this, R.string.settings_server_stopped, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        section(content, "PROFIL APLIKACJI")
        val profileCard = card()
        val profileContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileStatus = label(profileDescription(), 13f, 0xFFB4C2D1.toInt())
        profileContent.addView(profileStatus)
        profileContent.addView(label(
            "Profil decyduje, które moduły aplikacji są widoczne i dostępne. " +
                "Błędny kod nie odblokowuje żadnego modułu.",
            11f, 0xFF8792A2.toInt()
        ).apply { setPadding(0, dp(6), 0, 0) })
        val changeCodeButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "ZMIEŃ KOD PROFILU"
            setOnClickListener { showProfileCodeDialog() }
        }
        profileContent.addView(changeCodeButton, verticalParams(dp(12)))
        val resetButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "RESETUJ PROFIL (WYLOGUJ)"
            setTextColor(0xFFFF6B6B.toInt())
            setOnClickListener { confirmProfileReset() }
        }
        profileContent.addView(resetButton, verticalParams(dp(8)))
        profileCard.addView(profileContent)
        content.addView(profileCard, verticalParams(dp(4)))



        val about = card()
        val aboutContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        aboutContent.addView(label(getString(R.string.info_title), 17f, Color.WHITE, true))
        aboutContent.addView(label(getString(R.string.settings_app_version, appVersion()), 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(5), 0, 0)
        })
        aboutContent.addView(label(getString(R.string.settings_ip_address, localIp()), 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(4), 0, 0)
        })
        aboutContent.addView(label(getString(R.string.settings_bridge_port, AppWebFileServerController.PORT), 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(4), 0, 0)
        })
        val language = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.settings_language)
            setOnClickListener { toggleLanguage() }
        }
        aboutContent.addView(language, verticalParams(dp(12)))
        about.addView(aboutContent)
        content.addView(about, verticalParams(dp(12)))
    }

    private fun testConnection() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = address.text.toString().trim().ifBlank { "http://bruce.local" }
        val user = username.text.toString()
        val pass = password.text.toString()
        prefs.edit()
            .putString("bruce_url", baseUrl)
            .putString("bruce_user", user)
            .putString("bruce_pass", pass)
            .apply()
        status.setText(R.string.home_connection_checking)
        status.setTextColor(0xFFB4C2D1.toInt())
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                val normalizedUrl = when {
                    baseUrl.startsWith("http://", true) || baseUrl.startsWith("https://", true) -> baseUrl
                    else -> "http://$baseUrl"
                }.trimEnd('/')
                try {
                    val url = URL("$normalizedUrl/systeminfo")
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 4000
                        connection.readTimeout = 4000
                        connection.requestMethod = "GET"
                        if (user.isNotEmpty() && pass.isNotEmpty()) {
                            val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                            connection.setRequestProperty("Authorization", "Basic $token")
                        }
                        Result.success(connection.responseCode)
                    } finally {
                        connection.disconnect()
                    }
                } catch (exception: IOException) {
                    Log.e("BruceIR", "Bruce connection test failed", exception)
                    Result.failure(exception)
                } catch (exception: SecurityException) {
                    Log.e("BruceIR", "Bruce connection test was blocked", exception)
                    Result.failure(exception)
                }
            }
            val failure = response.exceptionOrNull()
            status.text = when {
                response.isSuccess && response.getOrThrow() in 200..299 ->
                    getString(R.string.home_connection_online, response.getOrThrow())
                response.isSuccess ->
                    getString(R.string.home_connection_response, response.getOrThrow())
                failure is UnknownHostException ->
                    getString(
                        R.string.settings_connection_host_not_found,
                        failure.message ?: "unknown"
                    )
                else -> getString(
                    R.string.home_connection_failed_detail,
                    failure?.localizedMessage ?: ""
                )
            }
            status.setTextColor(
                getColor(
                    if (response.isSuccess && response.getOrThrow() in 200..299) {
                        R.color.accent_green
                    } else {
                        R.color.accent_red
                    }
                )
            )
        }
    }

    private fun addSwitch(
        parent: LinearLayout,
        titleRes: Int,
        subtitleRes: Int,
        key: String,
        defaultValue: Boolean,
        onChanged: (Boolean) -> Unit = {}
    ) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val settingCard = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(label(getString(titleRes), 15f, Color.WHITE, true))
        copy.addView(label(getString(subtitleRes), 11f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChanged(checked)
            }
        }
        row.addView(toggle)
        settingCard.addView(row)
        settingCard.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        parent.addView(settingCard, verticalParams(dp(8)))
    }

    private fun toggleLanguage() {
        val current = AppCompatDelegate.getApplicationLocales()
        val next = if (current.isEmpty || current.get(0)?.language == "en") "pl" else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }

    private fun inputField(label: String, value: String, type: Int) = EditText(this).apply {
        hint = label
        setText(value)
        inputType = type
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF8290A2.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.button_glass_bg)
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(0xFF141B25.toInt())
        strokeColor = 0xFF283545.toInt()
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(label(title, 11f, 0xFF62CDB1.toInt(), true).apply {
            setPadding(dp(2), dp(4), dp(2), dp(8))
        })
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun localIp(): String = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val addresses = interfaces.nextElement().inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                    return@runCatching address.hostAddress ?: "?"
                }
            }
        }
        "?"
    }.getOrDefault("?")
}
