package com.example.bruceir

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class BruceBleTouchpadActivity : AppCompatActivity() {

    private lateinit var tvBridgeStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var touchPadArea: View
    private lateinit var etLiveText: EditText
    private lateinit var etScriptEditor: EditText
    private var lastX = 0f
    private var lastY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()

        BruceBleIrLink.setListener { status ->
            runOnUiThread {
                if (BruceBleIrLink.isConnected || status.contains("Connected", ignoreCase = true)) {
                    tvBridgeStatus.text = "🟢 Połączono z LilyGO (Mostek ESP32 BLE Aktywny)"
                    tvBridgeStatus.setTextColor(0xFF43C59E.toInt())
                } else {
                    tvBridgeStatus.text = "🔴 Status LilyGO: $status"
                    tvBridgeStatus.setTextColor(0xFFE53935.toInt())
                }
            }
        }
        BruceBleIrLink.connect(this)
    }

    override fun onDestroy() {
        BruceBleIrLink.setListener(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(100))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("TOUCHPAD & BAD BLE ENGINE", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Myszka, Klawiatura & Bad BLE", 22f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        tvStatus = text("Gesty dotykowe, klawiatura w czasie rzeczywistym i skrypty Bad BLE przesłane przez LilyGO.", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(tvStatus)

        // 0. LILYGO CONNECTION STATUS CARD
        val bridgeCard = card()
        val bridgeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvBridgeStatus = text("🔴 Łączenie z LilyGO (Mostek ESP32 BLE)...", 13f, 0xFFFFD54F.toInt(), true)
        val btnConnectBridge = MaterialButton(this).apply {
            text = "🔌 Połącz / Odśwież Połączenie z LilyGO"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.connect(this@BruceBleTouchpadActivity)
            }
        }
        bridgeLayout.addView(tvBridgeStatus)
        bridgeLayout.addView(btnConnectBridge, verticalParams(dp(6)))
        bridgeCard.addView(bridgeLayout)
        root.addView(bridgeCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 1. TOUCHPAD & MOUSE SECTION
        val touchCard = card()
        val touchLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        touchLayout.addView(text("🖲️ Touchpad (Przesuń palcem = ruch myszki)", 14f, 0xFF43C59E.toInt(), true).apply {
            setPadding(0, 0, 0, dp(6))
        })

        touchPadArea = View(this).apply {
            setBackgroundResource(R.drawable.button_glass_bg)
            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }
        }
        touchLayout.addView(touchPadArea, LinearLayout.LayoutParams(-1, dp(180)))

        val btnRowMouse = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnLeftClick = MaterialButton(this).apply {
            text = "🖱️ Lewy Klik (LilyGO)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@BruceBleTouchpadActivity, "bu click left")
                Toast.makeText(this@BruceBleTouchpadActivity, "Wysłano przez LilyGO: Lewy Klik", Toast.LENGTH_SHORT).show()
            }
        }
        val btnRightClick = MaterialButton(this).apply {
            text = "🖱️ Prawy Klik (LilyGO)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@BruceBleTouchpadActivity, "bu click right")
                Toast.makeText(this@BruceBleTouchpadActivity, "Wysłano przez LilyGO: Prawy Klik", Toast.LENGTH_SHORT).show()
            }
        }
        btnRowMouse.addView(btnLeftClick, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        btnRowMouse.addView(btnRightClick, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        touchLayout.addView(btnRowMouse, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        touchCard.addView(touchLayout)
        root.addView(touchCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. REAL-TIME KEYBOARD SECTION
        val kbCard = card()
        val kbLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kbLayout.addView(text("⌨️ Klawiatura w Czasie Rzeczywistym (przez LilyGO)", 14f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })

        etLiveText = EditText(this).apply {
            hint = "Wpisz tekst tutaj - trafi na urządzenie w czasie rzeczywistym..."
            setHintTextColor(0xFF748397.toInt())
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            doAfterTextChanged { editable ->
                val text = editable?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    val lastChar = text.last()
                    BruceBleIrLink.sendCustomCommand(this@BruceBleTouchpadActivity, "STRING $lastChar")
                }
            }
        }
        kbLayout.addView(etLiveText, verticalParams(dp(4)))

        val btnSendEnter = MaterialButton(this).apply {
            text = "↵ Wyślij Enter"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                BruceBleIrLink.sendCustomCommand(this@BruceBleTouchpadActivity, "ENTER")
                etLiveText.setText("")
                Toast.makeText(this@BruceBleTouchpadActivity, "Wysłano przez LilyGO: ENTER", Toast.LENGTH_SHORT).show()
            }
        }
        kbLayout.addView(btnSendEnter, verticalParams(dp(4)))
        kbCard.addView(kbLayout)
        root.addView(kbCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 3. BAD BLE RUBBER DUCKY SCRIPT GENERATOR
        val badBleCard = card()
        val badBleLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        badBleLayout.addView(text("🦆 Bad BLE / Rubber Ducky Generator & Executor", 14f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })

        val btnPresetPicker = MaterialButton(this).apply {
            text = "📋 Wybierz Gotowy Skrypt Bad BLE"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { showPresetScriptPicker() }
        }
        badBleLayout.addView(btnPresetPicker, verticalParams(dp(4)))

        etScriptEditor = EditText(this).apply {
            hint = "Edytor Ducky Script:\nGUI r\nDELAY 500\nSTRING notepad.exe\nENTER\nDELAY 500\nSTRING Hello from BruceIR Bad BLE!\nENTER"
            setHintTextColor(0xFF748397.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        badBleLayout.addView(etScriptEditor, verticalParams(dp(6)))

        val btnRunScript = MaterialButton(this).apply {
            text = "🚀 Wyślij & Uruchom Skrypt (przez LilyGO)"
            setBackgroundColor(0xFFE53935.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { executeDuckyScript() }
        }
        badBleLayout.addView(btnRunScript, verticalParams(dp(6)))

        badBleCard.addView(badBleLayout)
        root.addView(badBleCard)
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                    BruceBleIrLink.sendCustomCommand(this, "bu move ${dx.toInt()} ${dy.toInt()}")
                    tvStatus.text = "Myszka BLE (LilyGO): ΔX = %d, ΔY = %d".format(dx.toInt(), dy.toInt())
                }
            }
            MotionEvent.ACTION_UP -> {
                tvStatus.text = "Dotknięto (Kliknięcie Lewe przez LilyGO)"
            }
        }
    }

    private fun showPresetScriptPicker() {
        val presets = arrayOf(
            "1. Hello World (Otwórz Notatnik & Wpisz wiadomość)",
            "2. Windows System Info (Otwórz Cmd & Pobierz dane)",
            "3. Linux Terminal Test (Otwórz Terminal & Echo)",
            "4. Wyczyść Edytor"
        )
        AlertDialog.Builder(this)
            .setTitle("Wybierz gotowy skrypt Bad BLE")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> etScriptEditor.setText("GUI r\nDELAY 500\nSTRING notepad.exe\nENTER\nDELAY 500\nSTRING Hello World from BruceIR Bad BLE!\nENTER")
                    1 -> etScriptEditor.setText("GUI r\nDELAY 500\nSTRING cmd /k systeminfo\nENTER")
                    2 -> etScriptEditor.setText("CTRL ALT t\nDELAY 500\nSTRING echo 'Bad BLE Active'\nENTER")
                    3 -> etScriptEditor.setText("")
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun executeDuckyScript() {
        val script = etScriptEditor.text.toString().trim()
        if (script.isBlank()) {
            Toast.makeText(this, "Napisz lub wybierz skrypt przed uruchomieniem!", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = script.lines()
        Toast.makeText(this, "Wysyłanie skryptu Bad BLE (${lines.size} linii) do LilyGO...", Toast.LENGTH_LONG).show()
        lines.forEach { line ->
            if (line.isNotBlank()) {
                BruceBleIrLink.sendCustomCommand(this, line.trim())
            }
        }
    }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
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
