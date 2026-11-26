package com.example.bruceir

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class FieldWorkbenchActivity : AppCompatActivity() {
    private data class Tool(val icon: String, val title: Int, val subtitle: Int, val action: () -> Unit)
    private data class SectionViews(val title: TextView, val cards: List<MaterialCardView>, var isExpanded: Boolean = true)

    private val sections = mutableListOf<SectionViews>()
    private lateinit var emptySearchResult: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var connectionAddress: TextView
    private lateinit var connectionButton: MaterialButton
    private var connectionJob: Job? = null
    private var profileDialog: androidx.appcompat.app.AlertDialog? = null
    private var lockTicker: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)
        requestNotificationPermissionIfNeeded()
        if (!AppProfileManager.isConfigured(this)) {
            buildLockedScreen()
            showProfileSetupDialog()
        } else {
            buildScreen()
            showSavedConnectionState()
        }
    }

    override fun onResume() {
        super.onResume()
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("theme_sound_enabled", true)) {
            ThemeSoundController.play(this)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, 0)
            windowInsets
        }
    }

    override fun onPause() {
        ThemeSoundController.pause()
        super.onPause()
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        lockTicker?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    /** Launcher to Workbench - powiadomienia (cracker/widget/makra) wymagają zgody od API 33. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Ekran zastępczy widoczny pod bramką kodu - warsztat pozostaje zablokowany. */
    private fun buildLockedScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(7, 10, 15))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        root.addView(text("🔒", 46f, 0xFF43C59E.toInt()).apply { gravity = Gravity.CENTER })
        root.addView(text(getString(R.string.workbench_title), 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        })
        root.addView(text(
            "Warsztat jest zablokowany do czasu podania poprawnego kodu profilu.\n" +
                "Błędny kod nie odblokowuje żadnych modułów.",
            13f, 0xFF9EADBD.toInt()
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })
        root.addView(MaterialButton(this).apply {
            text = "WPISZ KOD PROFILU"
            setOnClickListener { showProfileSetupDialog() }
        }, LinearLayout.LayoutParams(-1, dp(50)))
        setContentView(root)
    }

    /**
     * Bramka profilu. Błędny kod NIE ustawia profilu i NIE odblokowuje ekranu:
     * dialog pozostaje otwarty, pokazuje błąd i licznik prób, a po 5 błędach
     * następuje 60-sekundowa blokada. Kod 7777 (pełny dostęp) musi być wpisany jawnie.
     */
    private fun showProfileSetupDialog() {
        val configured = AppProfileManager.isConfigured(this)
        lockTicker?.let { mainHandler.removeCallbacks(it) }
        profileDialog?.dismiss()

        val input = EditText(this).apply {
            hint = "Wpisz kod aktywacyjny / PIN..."
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8290A2.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        layout.addView(text("Wpisz kod aktywacyjny profilu aplikacji:", 13f, 0xFF9EADBD.toInt()))
        layout.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(8) })
        val hintView = text(
            if (configured) {
                "Aktywny profil: ${AppProfileManager.profileLabel(AppProfileManager.getProfileMode(this))}\n" +
                    "Kod profilu zna właściciel aplikacji (zobacz dokumentację projektu)."
            } else {
                "Kody profili są ustalone przez właściciela aplikacji (dokumentacja projektu).\n" +
                    "Błędny kod nie odblokuje żadnego modułu."
            },
            11f, 0xFF8792A2.toInt()
        )
        layout.addView(hintView)
        val errorView = text("", 12f, 0xFFFF6B6B.toInt()).apply {
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        layout.addView(errorView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔑 Aktywacja Profilu Aplikacji")
            .setView(layout)
            .setCancelable(configured)
            .setPositiveButton("ZATWIERDŹ", null)
            .setNegativeButton("ANULUJ", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            positive.setOnClickListener {
                submitProfileCode(dialog, input, errorView, positive)
            }
            negative?.setOnClickListener { dialog.dismiss() }
            // Bez ustawionego profilu ekran warsztatu pozostaje zablokowany: back nie zamyka bramki.
            dialog.setOnKeyListener { _, keyCode, _ -> !configured && keyCode == KeyEvent.KEYCODE_BACK }
            updateProfileLockState(dialog, positive, errorView)
        }
        dialog.show()
        profileDialog = dialog
    }

    private fun submitProfileCode(
        dialog: androidx.appcompat.app.AlertDialog,
        input: EditText,
        errorView: TextView,
        positive: android.widget.Button
    ) {
        if (AppProfileManager.lockSecondsRemaining(this) > 0L) {
            updateProfileLockState(dialog, positive, errorView)
            return
        }
        val raw = input.text.toString()
        if (raw.isBlank()) {
            errorView.text = "Wpisz kod aktywacyjny."
            errorView.visibility = View.VISIBLE
            return
        }
        when (val result = AppProfileManager.applyCode(this, raw)) {
            is AppProfileManager.CodeResult.Granted -> {
                Toast.makeText(
                    this,
                    "Profil ustawiony: ${AppProfileManager.profileLabel(result.mode)}",
                    Toast.LENGTH_SHORT
                ).show()
                errorView.visibility = View.GONE
                profileDialog = null
                dialog.dismiss()
                buildScreen()
                showSavedConnectionState()
            }
            is AppProfileManager.CodeResult.Invalid -> {
                input.setText("")
                errorView.text = "Nieprawidłowy kod. Pozostałe próby: ${result.attemptsRemaining}."
                errorView.visibility = View.VISIBLE
            }
            is AppProfileManager.CodeResult.Locked -> {
                input.setText("")
                updateProfileLockState(dialog, positive, errorView)
            }
        }
    }

    private fun updateProfileLockState(
        dialog: androidx.appcompat.app.AlertDialog,
        positive: android.widget.Button,
        errorView: TextView
    ) {
        lockTicker?.let { mainHandler.removeCallbacks(it) }
        val remaining = AppProfileManager.lockSecondsRemaining(this)
        if (remaining <= 0L) {
            positive.isEnabled = true
            return
        }
        positive.isEnabled = false
        errorView.text = "Zbyt wiele błędnych kodów. Spróbuj ponownie za ${remaining} s."
        errorView.visibility = View.VISIBLE
        val ticker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val left = AppProfileManager.lockSecondsRemaining(this@FieldWorkbenchActivity)
                if (left <= 0L) {
                    positive.isEnabled = true
                    errorView.visibility = View.GONE
                } else {
                    errorView.text = "Zbyt wiele błędnych kodów. Spróbuj ponownie za ${left} s."
                    errorView.visibility = View.VISIBLE
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        lockTicker = ticker
        mainHandler.postDelayed(ticker, 1000L)
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text(getString(R.string.workbench_eyebrow), 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text(getString(R.string.workbench_title), 29f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val musicEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("theme_sound_enabled", true)
        val musicButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(if (musicEnabled) R.string.workbench_music_on else R.string.workbench_music_off)
            contentDescription = getString(R.string.workbench_music_toggle)
            setTextColor(if (musicEnabled) 0xFF43C59E.toInt() else 0xFF9EADBD.toInt())
            setOnClickListener { toggleThemeSound(this) }
        }
        header.addView(musicButton, LinearLayout.LayoutParams(dp(50), dp(46)).apply { marginEnd = dp(6) })
        val settingsButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "⚙"
            contentDescription = getString(R.string.workbench_tool_settings)
            setOnClickListener { openSettings() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        val mode = AppProfileManager.getProfileMode(this)

        root.addView(text(
            if (mode == AppProfileManager.ProfileMode.ELEC_ONLY) "Dedykowany Warsztat Inżyniera Elektryka & Protokół Pomiary Zs"
            else "Organized Control Center & Advanced Field Operations",
            13f, 0xFF9EADBD.toInt()
        ).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        if (mode != AppProfileManager.ProfileMode.ELEC_ONLY && mode != AppProfileManager.ProfileMode.IR_ONLY) {
            addConnectionCard(root)
        }

        val search = EditText(this).apply {
            hint = getString(R.string.workbench_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8290A2.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundResource(R.drawable.button_glass_bg)
            contentDescription = getString(R.string.workbench_search_hint)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterTools(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(4)
            bottomMargin = dp(14)
        })

        // CATEGORY 1: IR & PILOTY
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.IR)) {
            addSectionHeader(root, "📡 KODY I PILOTY IR")
            addSectionTools(root, listOf(
                Tool("▣", R.string.workbench_tool_ir, R.string.workbench_tool_ir_desc) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("ACTION", "OPEN_IR_LIBRARY"))
                },
                Tool("🐬", R.string.workbench_tool_flipper_import, R.string.workbench_tool_flipper_import_desc) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("ACTION", "IR_IMPORT_FILE"))
                },
                Tool("◉", R.string.workbench_tool_live_capture_ble, R.string.workbench_tool_live_capture_ble_desc) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("ACTION", "LIVE_CAPTURE_BLE"))
                },
                Tool("⌁", R.string.workbench_tool_live_capture_wifi, R.string.workbench_tool_live_capture_wifi_desc) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("ACTION", "LIVE_CAPTURE_WIFI"))
                }
            ))
        }

        // CATEGORY 2: BEZPRZEWODOWE & PILOT BLE
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.WIRELESS)) {
            addSectionHeader(root, "📶 BEZPRZEWODOWE & PILOT BLE")
            addSectionTools(root, listOf(
                Tool("📺", R.string.workbench_tool_ble_tv_remote, R.string.workbench_tool_ble_tv_remote_desc) {
                    startActivity(Intent(this, BruceBleTvRemoteActivity::class.java))
                },
                Tool("🖲️", R.string.workbench_tool_touchpad, R.string.workbench_tool_touchpad_desc) {
                    startActivity(Intent(this, BruceBleTouchpadActivity::class.java))
                },
                Tool("📊", R.string.workbench_tool_nrf24, R.string.workbench_tool_nrf24_desc) {
                    startActivity(Intent(this, Nrf24Activity::class.java))
                },
                Tool("📡", R.string.workbench_tool_wifi_radar, R.string.workbench_tool_wifi_radar_desc) {
                    startActivity(Intent(this, WifiRadarActivity::class.java))
                },
                Tool("📻", R.string.workbench_tool_rf_analyzer, R.string.workbench_tool_rf_analyzer_desc) {
                    startActivity(Intent(this, RfAnalyzerActivity::class.java))
                },
                Tool("✨", R.string.workbench_tool_rf_generator, R.string.workbench_tool_rf_generator_desc) {
                    startActivity(Intent(this, BruceRfRemoteGeneratorActivity::class.java))
                },
                Tool("⚡", R.string.workbench_tool_console, R.string.workbench_tool_console_desc) {
                    startActivity(Intent(this, RemoteConsoleActivity::class.java))
                }
            ))
        }

        // CATEGORY 3: MAKRA I AUTOMATYKA
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.MACROS)) {
            addSectionHeader(root, "⌘ MAKRA I AUTOMATYKA")
            addSectionTools(root, listOf(
                Tool("⌘", R.string.workbench_tool_macros, R.string.workbench_tool_macros_desc) {
                    startActivity(Intent(this, MacroWorkbenchActivity::class.java))
                },
                Tool("⚡", R.string.workbench_tool_quick_macro, R.string.workbench_tool_quick_macro_desc) {
                    startActivity(Intent(this, QuickMacroBuilderActivity::class.java))
                },
                Tool("⌕", R.string.workbench_tool_smart_macro, R.string.workbench_tool_smart_macro_desc) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("ACTION", "OPEN_SMART_MACRO"))
                },
                Tool("⏻", R.string.workbench_tool_tv_b_gone, R.string.workbench_tool_tv_b_gone_desc) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("ACTION", "RUN_TV_B_GONE_BLAST"))
                }
            ))
        }

        // CATEGORY 4: BRUCE HARDWARE & KONSOLA
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.BRUCE)) {
            addSectionHeader(root, "💻 KONSOLA I SPRZĘT (BRUCE)")
            addSectionTools(root, listOf(
                Tool("⌘", R.string.workbench_tool_console, R.string.workbench_tool_console_desc) {
                    startActivity(Intent(this, RemoteConsoleActivity::class.java))
                },
                Tool("↗", R.string.workbench_tool_web, R.string.workbench_tool_web_desc) {
                    startActivity(Intent(this, BruceWebActivity::class.java))
                }
            ))
        }

        // CATEGORY 5: OPERACJE POLOWE I SIECIOWE
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.NETWORK)) {
            addSectionHeader(root, "🌐 OPERACJE POLOWE I SIECIOWE")
            addSectionTools(root, listOf(
                Tool("⚡", R.string.workbench_tool_wifi_aggressive, R.string.workbench_tool_wifi_aggressive_desc) {
                    startActivity(Intent(this, AggressiveWifiScanActivity::class.java))
                },
                Tool("🌐", R.string.workbench_tool_net_advanced, R.string.workbench_tool_net_advanced_desc) {
                    startActivity(Intent(this, AdvancedNetworkScannerActivity::class.java))
                },
                Tool("⌁", R.string.workbench_tool_wifi, R.string.workbench_tool_wifi_desc) {
                    startActivity(Intent(this, WifiSurveyActivity::class.java))
                },
                Tool("⌖", R.string.workbench_tool_mapper, R.string.workbench_tool_mapper_desc) {
                    startActivity(Intent(this, LanMapperActivity::class.java))
                },
                Tool("👿", R.string.workbench_tool_evil_portal, R.string.workbench_tool_evil_portal_desc) {
                    startActivity(Intent(this, EvilPortalGeneratorActivity::class.java))
                },
                Tool("☼", R.string.workbench_tool_lux, R.string.workbench_tool_lux_desc) {
                    startActivity(Intent(this, LuxActivity::class.java))
                }
            ))
        }

        // CATEGORY 6: ELEKTRYKA & POMIARY
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.ELECTRICIAN)) {
            addSectionHeader(root, "⚡ ELEKTRYKA & POMIARY")
            addSectionTools(root, listOf(
                Tool("⚡", R.string.workbench_tool_electrician, R.string.workbench_tool_electrician_desc) {
                    startActivity(Intent(this, ElectricianWorkbenchActivity::class.java))
                }
            ))
        }

        // CATEGORY 7: NARZĘDZIA ZAAWANSOWANE (każde narzędzie bramkowane osobno)
        addSectionHeader(root, "⚙ NARZĘDZIA ZAAWANSOWANE")
        val advancedTools = mutableListOf(
            Tool("🔑", R.string.workbench_tool_profile_code, R.string.workbench_tool_profile_code_desc) {
                showProfileSetupDialog()
            }
        )
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.IR)) {
            advancedTools += Tool("📡", R.string.workbench_tool_multiblast, R.string.workbench_tool_multiblast_desc) {
                startActivity(Intent(this, MultiBlastActivity::class.java))
            }
        }
        if (AppProfileManager.isGroupAllowed(mode, AppProfileManager.ModuleGroup.NETWORK)) {
            advancedTools += Tool("⇄", R.string.workbench_tool_pcap, R.string.workbench_tool_pcap_desc) {
                startActivity(Intent(this, PcapConverterActivity::class.java))
            }
        }
        addSectionTools(root, advancedTools)

        emptySearchResult = text(getString(R.string.workbench_no_results), 14f, 0xFF9EADBD.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            visibility = View.GONE
        }
        root.addView(emptySearchResult)
        root.addView(text(getString(R.string.workbench_footer), 11f, 0xFF748397.toInt()).apply {
            setPadding(dp(2), dp(16), dp(2), 0)
        })
    }

    private fun addSectionHeader(root: LinearLayout, title: String) {
        val sectionTitle = text("▶  $title", 12f, 0xFF62CDB1.toInt(), true).apply {
            setPadding(dp(4), dp(16), dp(4), dp(8))
        }
        root.addView(sectionTitle)
    }

    private fun addSectionTools(root: LinearLayout, tools: List<Tool>) {
        val sectionTitleView = root.getChildAt(root.childCount - 1) as TextView
        val cards = tools.map { addToolCard(root, it) }
        val section = SectionViews(sectionTitleView, cards, isExpanded = false)
        sections += section

        section.cards.forEach { it.visibility = View.GONE }

        sectionTitleView.setOnClickListener {
            section.isExpanded = !section.isExpanded
            val arrow = if (section.isExpanded) "▼" else "▶"
            val cleanTitle = sectionTitleView.text.toString().replace(Regex("^[▼▶]\\s*"), "")
            sectionTitleView.text = "$arrow  $cleanTitle"
            section.cards.forEach { card ->
                card.visibility = if (section.isExpanded) View.VISIBLE else View.GONE
            }
        }
    }

    private fun addConnectionCard(parent: LinearLayout) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(text(getString(R.string.workbench_device_title), 16f, Color.WHITE, true))
        connectionAddress = text("", 12f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(3), 0, 0) }
        titleColumn.addView(connectionAddress)
        row.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        connectionButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.workbench_device_test)
            setOnClickListener { testBruceConnection() }
        }
        row.addView(connectionButton)
        column.addView(row)
        connectionStatus = text(getString(R.string.workbench_device_not_checked), 12f, 0xFFB4C2D1.toInt()).apply {
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(connectionStatus)
        val card = card().apply { addView(column) }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
    }

    private fun showSavedConnectionState() {
        if (!::connectionAddress.isInitialized || !::connectionStatus.isInitialized) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val address = prefs.getString("bruce_url", "http://bruce.local").orEmpty()
        connectionAddress.text = getString(R.string.workbench_device_address, address)
        val savedStatus = prefs.getString("workbench_bruce_status", null)
        if (savedStatus != null) {
            connectionStatus.text = savedStatus
            val online = prefs.getBoolean("workbench_bruce_online", false)
            connectionStatus.setTextColor(getColor(if (online) R.color.accent_green else R.color.accent_red))
        }
    }

    private fun testBruceConnection() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("bruce_url", "http://bruce.local").orEmpty().trim()
        val username = prefs.getString("bruce_user", "admin").orEmpty()
        val password = prefs.getString("bruce_pass", "bruce").orEmpty()
        connectionJob?.cancel()
        connectionButton.isEnabled = false
        connectionStatus.setText(R.string.home_connection_checking)
        connectionStatus.setTextColor(0xFFB4C2D1.toInt())
        connectionJob = lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = if (baseUrl.startsWith("http://", true) || baseUrl.startsWith("https://", true)) {
                        baseUrl.trimEnd('/')
                    } else {
                        "http://$baseUrl".trimEnd('/')
                    }
                    val connection = URL("$normalized/systeminfo").openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 4000
                        connection.readTimeout = 4000
                        connection.requestMethod = "GET"
                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                            connection.setRequestProperty("Authorization", "Basic $token")
                        }
                        connection.responseCode
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(-1)
            }
            val online = code in 200..299
            val result = when {
                online -> getString(R.string.home_connection_online, code)
                code in 100..599 -> getString(R.string.home_connection_response, code)
                else -> getString(R.string.home_connection_failed)
            }
            connectionStatus.text = result
            connectionStatus.setTextColor(getColor(if (online) R.color.accent_green else R.color.accent_red))
            prefs.edit().putString("workbench_bruce_status", result).putBoolean("workbench_bruce_online", online).apply()
            connectionButton.isEnabled = true
        }
    }

    private fun addToolCard(parent: LinearLayout, tool: Tool): MaterialCardView {
        val card = card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { tool.action() }
            contentDescription = "${getString(tool.title)}. ${getString(tool.subtitle)}"
            tag = contentDescription.toString().lowercase()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = tool.icon
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFF62CDB1.toInt())
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        labels.addView(text(getString(tool.title), 15f, Color.WHITE, true))
        labels.addView(text(getString(tool.subtitle), 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("›", 23f, 0xFF62CDB1.toInt(), true))
        card.addView(row)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return card
    }

    private fun filterTools(query: String) {
        val needle = query.trim().lowercase()
        sections.forEach { section ->
            section.cards.forEach { card ->
                card.visibility = if (needle.isEmpty() || (card.tag as String).contains(needle)) View.VISIBLE else View.GONE
            }
            section.title.visibility = if (section.cards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        }
        val anyVisible = sections.any { section -> section.cards.any { it.visibility == View.VISIBLE } }
        emptySearchResult.visibility = if (needle.isNotEmpty() && !anyVisible) View.VISIBLE else View.GONE
    }

    private fun toggleThemeSound(button: MaterialButton) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = !prefs.getBoolean("theme_sound_enabled", true)
        prefs.edit().putBoolean("theme_sound_enabled", enabled).apply()
        button.text = getString(if (enabled) R.string.workbench_music_on else R.string.workbench_music_off)
        button.setTextColor(if (enabled) 0xFF43C59E.toInt() else 0xFF9EADBD.toInt())
        if (enabled) ThemeSoundController.play(this) else ThemeSoundController.pause()
    }

    private fun openSettings() {
        startActivity(Intent(this, WorkbenchSettingsActivity::class.java))
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(0xFF141B25.toInt())
        strokeColor = 0xFF283545.toInt()
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
}
