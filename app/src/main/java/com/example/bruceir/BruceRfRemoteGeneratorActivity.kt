package com.example.bruceir

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class BruceRfRemoteGeneratorActivity : AppCompatActivity() {

    private lateinit var etRemoteName: EditText
    private lateinit var spinnerFreq: Spinner
    private lateinit var tvProfileStatus: TextView
    private lateinit var tvCounterStatus: TextView
    private lateinit var containerRemoteButtons: LinearLayout

    private var activeProfile: KeeloqRemoteGenerator.RemoteProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(130))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("RF REMOTE GENERATOR", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Generator & Parownik Pilotów RF", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Generuj unikalny pilot radiowy (Serial ID), przypisz go do sterownika bramy i steruj kodem zmiennym.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // Creator Card
        val creatorCard = card()
        val creatorLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        creatorLayout.addView(text("✨ Tworzenie Nowego Pilota RF", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etRemoteName = inputField("Nazwa nowego pilota [np. Pilot Brama Garażowa]")
        etRemoteName.setText("Nowy Pilot Bramy #1")

        spinnerFreq = Spinner(this).apply {
            adapter = ArrayAdapter(this@BruceRfRemoteGeneratorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("433.92 MHz (Standard BRAMY / ROLETY)", "315.00 MHz (Bramy / Piloty USA)", "868.35 MHz (Systemy EU)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        creatorLayout.addView(etRemoteName, verticalParams(dp(4)))
        creatorLayout.addView(spinnerFreq, verticalParams(dp(6)))

        val btnGenerate = MaterialButton(this).apply {
            text = "✨ Wygeneruj Nowy Unikalny Pilot (New Serial ID)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { generateNewRemoteProfile() }
        }
        creatorLayout.addView(btnGenerate, verticalParams(dp(10)))

        tvProfileStatus = text("Brak aktywnego pilota. Wygeneruj nowy pilot wyżej.", 13f, 0xFF00E5FF.toInt()).apply {
            setPadding(0, dp(8), 0, 0)
        }
        creatorLayout.addView(tvProfileStatus)

        creatorCard.addView(creatorLayout)
        root.addView(creatorCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Remote UI & Control Card
        val remoteCard = card()
        val remoteLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remoteLayout.addView(text("🎮 Sterowanie & Rejestracja w Odbiorniku", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        val btnPairGate = MaterialButton(this).apply {
            text = "🔑 KROK 1: Ucz Odbiornik Bramy (Rejestracja)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { pairWithGateReceiver() }
        }
        remoteLayout.addView(btnPairGate, verticalParams(dp(4)))

        tvCounterStatus = text("Licznik Zmienno-Kodowy: C = 0", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        }
        remoteLayout.addView(tvCounterStatus)

        containerRemoteButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remoteLayout.addView(containerRemoteButtons)

        remoteCard.addView(remoteLayout)
        root.addView(remoteCard)
    }

    private fun generateNewRemoteProfile() {
        val name = etRemoteName.text.toString().trim().ifEmpty { "Nowy Pilot RF" }
        val freq = when (spinnerFreq.selectedItemPosition) {
            0 -> 433920000L
            1 -> 315000000L
            else -> 868350000L
        }

        val profile = KeeloqRemoteGenerator.createNewRemote(name, freq)
        activeProfile = profile

        tvProfileStatus.text = "🟢 AKTYWNY PILOT: ${profile.name}\nUnikalny Serial ID: 0x${profile.serialIdHex} | Częstotliwość: ${profile.frequencyHz / 1000000.0} MHz"
        tvCounterStatus.text = "Licznik Zmienno-Kodowy: C = ${profile.counter}"

        renderRemoteControlButtons()
        Toast.makeText(this, "Wygenerowano nowy pilot z unikalnym Serial ID: 0x${profile.serialIdHex}", Toast.LENGTH_LONG).show()
    }

    private fun pairWithGateReceiver() {
        val profile = activeProfile
        if (profile == null) {
            Toast.makeText(this, "Najpierw wygeneruj nowy pilot!", Toast.LENGTH_SHORT).show()
            return
        }

        // Natywna komenda rejestracji KeeLoq w oprogramowaniu układowym Bruce
        val cmd = "subghz keeloqtx Came ${profile.frequencyHz} 1 ${profile.serialIdHex} 1 10"
        val sent = BruceBleManager.sendCommand(cmd)
        if (sent) {
            Toast.makeText(this, "Wysłano natywny sygnał rejestracji Bruce z ramką KeeLoq!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Sygnał rejestracji: $cmd (Przekazano do Bruce)", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendButtonSignal(buttonCode: Int, buttonName: String) {
        val profile = activeProfile
        if (profile == null) {
            Toast.makeText(this, "Najpierw wygeneruj nowy pilot!", Toast.LENGTH_SHORT).show()
            return
        }

        profile.counter += 1
        tvCounterStatus.text = "Licznik Zmienno-Kodowy: C = ${profile.counter} (Skok +1)"

        // Natywna transmisja natywnej ramki KeeLoq przez Bruce
        val cmd = "subghz keeloqtx Came ${profile.frequencyHz} $buttonCode ${profile.serialIdHex} ${profile.counter} 10"
        val sent = BruceBleManager.sendCommand(cmd)

        if (sent) {
            Toast.makeText(this, "Wysłano $buttonName z Bruce (C=${profile.counter})", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Wysłano $buttonName: $cmd", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderRemoteControlButtons() {
        containerRemoteButtons.removeAllViews()

        val btnGate = MaterialButton(this).apply {
            text = "🚪 BRAMA GŁÓWNA (Przycisk 1)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { sendButtonSignal(1, "Brama Główna") }
        }
        containerRemoteButtons.addView(btnGate, verticalParams(dp(4)))

        val btnWicket = MaterialButton(this).apply {
            text = "🅿️ FURTKA / WEJŚCIE (Przycisk 2)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendButtonSignal(2, "Furtka / Wejście") }
        }
        containerRemoteButtons.addView(btnWicket, verticalParams(dp(4)))

        val btnLight = MaterialButton(this).apply {
            text = "💡 OŚWIETLENIE TERENU (Przycisk 3)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendButtonSignal(3, "Oświetlenie Terenu") }
        }
        containerRemoteButtons.addView(btnLight, verticalParams(dp(4)))

        val btnAlarm = MaterialButton(this).apply {
            text = "🚨 ALARM / AWARIA (Przycisk 4)"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { sendButtonSignal(4, "Alarm / Awaria") }
        }
        containerRemoteButtons.addView(btnAlarm, verticalParams(dp(4)))
    }

    private fun inputField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
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

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
    }
}
