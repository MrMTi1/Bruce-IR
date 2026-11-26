package com.example.bruceir

import android.content.ContentValues
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ElectricianWorkbenchActivity : AppCompatActivity(), SensorEventListener {

    private class CeilingDiagramView(context: Context) : View(context) {
        var roomLength: Double = 6.0
        var roomWidth: Double = 4.0
        var nL: Int = 3
        var nW: Int = 2

        private val bgPaint = Paint().apply { color = 0xFF141B25.toInt(); style = Paint.Style.FILL }
        private val borderPaint = Paint().apply { color = 0xFF43C59E.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f }
        private val gridPaint = Paint().apply { color = 0xFF283545.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f) }
        private val lampPaint = Paint().apply { color = 0xFFFFD54F.toInt(); style = Paint.Style.FILL }
        private val textPaint = Paint().apply { color = Color.WHITE; textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val tapeTextPaint = Paint().apply { color = 0xFF00E5FF.toInt(); textSize = 19f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val spacingTextPaint = Paint().apply { color = 0xFFFF9800.toInt(); textSize = 19f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val spacingTextRightPaint = Paint().apply { color = 0xFFFF9800.toInt(); textSize = 19f; isFakeBoldText = true; textAlign = Paint.Align.LEFT }
        private val dimPaint = Paint().apply { color = 0xFF9EADBD.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0 || roomLength <= 0.0 || roomWidth <= 0.0) return

            val paddingLeft = 80f
            val paddingTop = 65f
            val paddingRight = 95f
            val paddingBottom = 65f

            val availableW = width - (paddingLeft + paddingRight)
            val availableH = height - (paddingTop + paddingBottom)

            val scale = Math.min(availableW / roomLength.toFloat(), availableH / roomWidth.toFloat())
            val rectW = (roomLength * scale).toFloat()
            val rectH = (roomWidth * scale).toFloat()

            val left = paddingLeft + (availableW - rectW) / 2f
            val top = paddingTop + (availableH - rectH) / 2f
            val right = left + rectW
            val bottom = top + rectH

            canvas.drawRect(left, top, right, bottom, bgPaint)
            canvas.drawRect(left, top, right, bottom, borderPaint)

            val dL_cm = (roomLength * 100.0) / nL
            val aL_cm = dL_cm / 2.0
            val dW_cm = (roomWidth * 100.0) / nW
            val aW_cm = dW_cm / 2.0

            val headerText = String.format(Locale.getDefault(), "Długość L=%.2fm | Szerokość W=%.2fm", roomLength, roomWidth)
            canvas.drawText(headerText, width / 2f, top - 22f, dimPaint)

            val dL = rectW / nL
            val aL = dL / 2f
            val dW = rectH / nW
            val aW = dW / 2f

            for (i in 0 until nL) {
                val cx = left + aL + i * dL
                val tapeCm = aL_cm + i * dL_cm
                canvas.drawLine(cx, top, cx, bottom, gridPaint)
                canvas.drawText(String.format(Locale.getDefault(), "▼ %.0fcm", tapeCm), cx, top + 24f, tapeTextPaint)
            }

            for (j in 0 until nW) {
                val cy = top + aW + j * dW
                val tapeCm = aW_cm + j * dW_cm
                canvas.drawLine(left, cy, right, cy, gridPaint)
                canvas.drawText(String.format(Locale.getDefault(), "▶ %.0fcm", tapeCm), left + 45f, cy - 8f, tapeTextPaint)
            }

            for (i in 0..nL) {
                val segCenterX = when (i) {
                    0 -> left + aL / 2f
                    nL -> right - aL / 2f
                    else -> left + aL + (i - 0.5f) * dL
                }
                val segValCm = if (i == 0 || i == nL) aL_cm else dL_cm
                canvas.drawText(String.format(Locale.getDefault(), "↔%.0f", segValCm), segCenterX, bottom + 25f, spacingTextPaint)
            }

            for (j in 0..nW) {
                val segCenterY = when (j) {
                    0 -> top + aW / 2f
                    nW -> bottom - aW / 2f
                    else -> top + aW + (j - 0.5f) * dW
                }
                val segValCm = if (j == 0 || j == nW) aW_cm else dW_cm
                canvas.drawText(String.format(Locale.getDefault(), "↕%.0f", segValCm), right + 10f, segCenterY + 6f, spacingTextRightPaint)
            }

            var count = 1
            for (i in 0 until nL) {
                val cx = left + aL + i * dL
                for (j in 0 until nW) {
                    val cy = top + aW + j * dW
                    canvas.drawCircle(cx, cy, 16f, lampPaint)
                    canvas.drawText("L$count", cx, cy + 28f, textPaint)
                    count++
                }
            }
        }
    }

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var tvLuxDisplay: TextView

    // Ohm calculator fields
    private lateinit var etOhmVolt: EditText
    private lateinit var etOhmAmp: EditText
    private lateinit var etOhmRes: EditText
    private lateinit var etOhmPower: EditText
    private lateinit var spinnerOhmType: Spinner
    private lateinit var etOhmCosPhi: EditText
    private lateinit var tvOhmResult: TextView

    // Voltage drop fields
    private lateinit var etDropCurrent: EditText
    private lateinit var etDropLength: EditText
    private lateinit var etDropArea: EditText
    private lateinit var tvDropResult: TextView

    // Breaker Options (Type B, C, D)
    private val breakerOptionsFull = listOf(
        "B6 (Max Zs: 7.67Ω)", "B10 (Max Zs: 4.60Ω)", "B16 (Max Zs: 2.87Ω)", "B20 (Max Zs: 2.30Ω)", "B25 (Max Zs: 1.84Ω)", "B32 (Max Zs: 1.44Ω)", "B40 (Max Zs: 1.15Ω)", "B50 (Max Zs: 0.92Ω)", "B63 (Max Zs: 0.73Ω)",
        "C6 (Max Zs: 3.83Ω)", "C10 (Max Zs: 2.30Ω)", "C16 (Max Zs: 1.44Ω)", "C20 (Max Zs: 1.15Ω)", "C25 (Max Zs: 0.92Ω)", "C32 (Max Zs: 0.72Ω)", "C40 (Max Zs: 0.58Ω)", "C50 (Max Zs: 0.46Ω)", "C63 (Max Zs: 0.37Ω)",
        "D6 (Max Zs: 1.92Ω)", "D10 (Max Zs: 1.15Ω)", "D16 (Max Zs: 0.72Ω)", "D20 (Max Zs: 0.58Ω)", "D25 (Max Zs: 0.46Ω)", "D32 (Max Zs: 0.36Ω)", "D40 (Max Zs: 0.29Ω)", "D50 (Max Zs: 0.23Ω)", "D63 (Max Zs: 0.18Ω)"
    )
    private val maxZsValuesFull = listOf(
        7.67, 4.60, 2.87, 2.30, 1.84, 1.44, 1.15, 0.92, 0.73,
        3.83, 2.30, 1.44, 1.15, 0.92, 0.72, 0.58, 0.46, 0.37,
        1.92, 1.15, 0.72, 0.58, 0.46, 0.36, 0.29, 0.23, 0.18
    )

    // IPZ fields
    private lateinit var etIpzVoltage: EditText
    private lateinit var etIpzZs: EditText
    private lateinit var spinnerBreaker: Spinner
    private lateinit var tvIpzResult: TextView

    // Load & Breaker sizing fields
    private lateinit var etLoadPower: EditText
    private lateinit var spinnerPhase: Spinner
    private lateinit var etLoadCosPhi: EditText
    private lateinit var tvLoadResult: TextView

    // Fixture Layout Installer fields
    private lateinit var etLightLength: EditText
    private lateinit var etLightWidth: EditText
    private lateinit var etLightCount: EditText
    private lateinit var spinnerLightLayout: Spinner
    private lateinit var tvLightResult: TextView
    private lateinit var diagramContainer: FrameLayout

    // Scale calculator fields
    private lateinit var spinnerScale: Spinner
    private lateinit var etScaleCustom: EditText
    private lateinit var etScalePaperCm: EditText
    private lateinit var etScaleRealMeters: EditText
    private lateinit var tvScaleResult: TextView

    // Cable Sizing fields
    private lateinit var etCableCurrent: EditText
    private lateinit var etCableLength: EditText
    private lateinit var spinnerCableMat: Spinner
    private lateinit var spinnerCablePhase: Spinner
    private lateinit var tvCableResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.ELECTRICIAN)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)
        buildScreen()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            tvLuxDisplay.text = String.format(Locale.getDefault(), "%.0f lx (%s)", lux, when {
                lux < 10 -> "Ciemno"
                lux < 400 -> "Biuro / Dom"
                lux < 1000 -> "Jasno"
                else -> "Pełne słońce"
            })
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
        titleStack.addView(text(getString(R.string.elec_eyebrow), 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text(getString(R.string.elec_title), 28f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text(getString(R.string.elec_intro), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // 0. INSPECTOR MODE LAUNCHER CARD
        val inspectorCard = card().apply {
            setOnClickListener {
                startActivity(Intent(this@ElectricianWorkbenchActivity, ElectricalInspectorActivity::class.java))
            }
        }
        val inspectorLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inspectorLayout.addView(text("📋 Tryb Pomiarowca (Protokół Zs)", 16f, 0xFF43C59E.toInt(), true).apply { setPadding(0, 0, 0, dp(4)) })
        inspectorLayout.addView(text("Automatyczna numeracja obwodów, niezależny dobór zabezpieczeń, weryfikacja Pozytyw/Negatyw i zapis do pliku CSV.", 12f, 0xFF9EADBD.toInt()))
        inspectorCard.addView(inspectorLayout)
        root.addView(inspectorCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 1. FIXTURE LAYOUT FOR INSTALLER (MOVED TO TOP!)
        val lightCard = card()
        val lightLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lightLayout.addView(text("💡 Rozmieszczenie Opraw dla Montera (w cm)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etLightLength = inputField("Długość pomieszczenia L (m) [np. 6.0]")
        etLightWidth = inputField("Szerokość pomieszczenia W (m) [np. 4.0]")
        etLightCount = inputField("Liczba opraw (szt.) [np. 6]")
        spinnerLightLayout = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Siatka / Prostokąt (Rzędy × Kolumny)", "W Jednej Linii (1 Rząd wzdłuż L)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        lightLayout.addView(etLightLength, verticalParams(dp(4)))
        lightLayout.addView(etLightWidth, verticalParams(dp(4)))
        lightLayout.addView(etLightCount, verticalParams(dp(4)))
        lightLayout.addView(spinnerLightLayout, verticalParams(dp(6)))

        val btnCalcLight = MaterialButton(this).apply {
            text = "Oblicz Rozstaw i Rysuj Rzut (cm)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { calculateLightingInstaller() }
        }
        lightLayout.addView(btnCalcLight, verticalParams(dp(8)))

        val btnSaveDiagramPng = MaterialButton(this).apply {
            text = "🖼️ Zapisz Rzut 2D do Pliku (PNG)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { saveDiagramAsPng() }
        }
        lightLayout.addView(btnSaveDiagramPng, verticalParams(dp(6)))

        tvLightResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        lightLayout.addView(tvLightResult)

        diagramContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }
        lightLayout.addView(diagramContainer, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(10) })

        lightCard.addView(lightLayout)
        root.addView(lightCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. BUSBAR / TRACK LAYOUT CALCULATOR CARD (MOVED TO TOP!)
        root.addView(buildBusbarCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 3. LUX METER CARD
        val luxCard = card()
        val luxLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        luxLayout.addView(text("☀️ ${getString(R.string.elec_lux_title)}", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(6)) })
        tvLuxDisplay = text("Sensors initializing...", 24f, 0xFF43C59E.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        luxLayout.addView(tvLuxDisplay)
        luxCard.addView(luxLayout)
        root.addView(luxCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 4. OHM'S LAW & POWER CALCULATOR (DC & AC)
        val ohmCard = card()
        val ohmLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ohmLayout.addView(text("⚡ ${getString(R.string.elec_ohm_title)}", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etOhmCosPhi = inputField("Współczynnik mocy cosφ [np. 0.95]")
        etOhmCosPhi.setText("1.0")
        etOhmCosPhi.isEnabled = false

        spinnerOhmType = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Prąd Stały (DC, cosφ = 1.0)", "Prąd Zmienny (AC 1-Faza)"))
            setBackgroundResource(R.drawable.button_glass_bg)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        etOhmCosPhi.setText("1.0")
                        etOhmCosPhi.isEnabled = false
                    } else {
                        etOhmCosPhi.setText("0.95")
                        etOhmCosPhi.isEnabled = true
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        etOhmVolt = inputField("Napięcie (V) [np. 230]")
        etOhmAmp = inputField("Prąd (A) [np. 10]")
        etOhmRes = inputField("Rezystancja (Ω)")
        etOhmPower = inputField("Moc (W)")

        ohmLayout.addView(spinnerOhmType, verticalParams(dp(4)))
        ohmLayout.addView(etOhmCosPhi, verticalParams(dp(4)))
        ohmLayout.addView(label("Wpisz dowolne 2 wartości:"))
        ohmLayout.addView(etOhmVolt, verticalParams(dp(4)))
        ohmLayout.addView(etOhmAmp, verticalParams(dp(4)))
        ohmLayout.addView(etOhmRes, verticalParams(dp(4)))
        ohmLayout.addView(etOhmPower, verticalParams(dp(4)))

        val btnCalcOhm = MaterialButton(this).apply {
            text = getString(R.string.elec_calc_ohm)
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { calculateOhm() }
        }
        ohmLayout.addView(btnCalcOhm, verticalParams(dp(8)))

        tvOhmResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        ohmLayout.addView(tvOhmResult)
        ohmCard.addView(ohmLayout)
        root.addView(ohmCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 5. SCALE CALCULATOR (PLAN SCALE 1:50, 1:100...)
        val scaleCard = card()
        val scaleLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaleLayout.addView(text("📐 Kalkulator Skali Rysunku / Planu (1:50, 1:100)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etScaleCustom = inputField("Skala własna 1:X [np. 75]").apply { isEnabled = false }
        etScalePaperCm = inputField("Wymiar na planie (cm) [np. 4.5]")
        etScaleRealMeters = inputField("Wymiar w terenie (m) [np. 2.25]")

        val scaleWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                calculateScale()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }

        etScaleCustom.addTextChangedListener(scaleWatcher)
        etScalePaperCm.addTextChangedListener(scaleWatcher)
        etScaleRealMeters.addTextChangedListener(scaleWatcher)

        spinnerScale = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1:50 (Plany instalacji)", "1:100 (Rzuty budynków)", "1:20 (Detale / Rozdzielnice)", "1:25", "1:200 (Zagospodarowanie terenu)", "1:500 (Działka / Przyłącza)", "1:1 (Skala rzeczywista)", "Własna skala (1:X)"))
            setBackgroundResource(R.drawable.button_glass_bg)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    etScaleCustom.isEnabled = (position == 7)
                    calculateScale()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        scaleLayout.addView(spinnerScale, verticalParams(dp(4)))
        scaleLayout.addView(etScaleCustom, verticalParams(dp(4)))
        scaleLayout.addView(label("Wpisz wymiar z linijki (cm) LUB wymiar z terenu (m):"))
        scaleLayout.addView(etScalePaperCm, verticalParams(dp(4)))
        scaleLayout.addView(etScaleRealMeters, verticalParams(dp(4)))

        tvScaleResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        scaleLayout.addView(tvScaleResult)
        scaleCard.addView(scaleLayout)
        root.addView(scaleCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 6. CABLE SIZING (DOBÓR PRZEWODU)
        val cableCard = card()
        val cableLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cableLayout.addView(text("🔌 Dobór Przekroju Przewodu (Sizing)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etCableCurrent = inputField("Prąd obciążenia Ib (A) [np. 16]")
        etCableLength = inputField("Długość trasy L (m) [np. 25]")
        spinnerCableMat = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Miedź (Cu)", "Aluminium (Al)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        spinnerCablePhase = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1-Faza (230V)", "3-Fazy (400V)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        cableLayout.addView(etCableCurrent, verticalParams(dp(4)))
        cableLayout.addView(etCableLength, verticalParams(dp(4)))
        cableLayout.addView(spinnerCableMat, verticalParams(dp(6)))
        cableLayout.addView(spinnerCablePhase, verticalParams(dp(6)))

        val btnCalcCable = MaterialButton(this).apply {
            text = "Dobierz Przekrój Przewodu"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { calculateCableSizing() }
        }
        cableLayout.addView(btnCalcCable, verticalParams(dp(8)))

        tvCableResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        cableLayout.addView(tvCableResult)
        cableCard.addView(cableLayout)
        root.addView(cableCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 7. VOLTAGE DROP CALCULATOR
        val dropCard = card()
        val dropLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dropLayout.addView(text("📉 ${getString(R.string.elec_drop_title)}", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etDropCurrent = inputField("Prąd obciążenia (A) [np. 16]")
        etDropLength = inputField("Długość przewodu (m) [np. 25]")
        etDropArea = inputField("Przekrój przewodu (mm²) [np. 2.5]")

        dropLayout.addView(etDropCurrent, verticalParams(dp(4)))
        dropLayout.addView(etDropLength, verticalParams(dp(4)))
        dropLayout.addView(etDropArea, verticalParams(dp(4)))

        val btnCalcDrop = MaterialButton(this).apply {
            text = getString(R.string.elec_calc_drop)
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { calculateVoltageDrop() }
        }
        dropLayout.addView(btnCalcDrop, verticalParams(dp(8)))

        tvDropResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        dropLayout.addView(tvDropResult)
        dropCard.addView(dropLayout)
        root.addView(dropCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 8. IPZ & PROTECTION VERIFICATION
        val ipzCard = card()
        val ipzLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ipzLayout.addView(text("🛡️ ${getString(R.string.elec_ipz_title)}", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etIpzVoltage = inputField("Napięcie fazowe U0 (V) [np. 230]")
        etIpzZs = inputField("Zmierzona impedancja pętli Zs (Ω) [np. 1.2]")

        spinnerBreaker = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, breakerOptionsFull)
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        ipzLayout.addView(etIpzVoltage, verticalParams(dp(4)))
        ipzLayout.addView(etIpzZs, verticalParams(dp(4)))
        ipzLayout.addView(spinnerBreaker, verticalParams(dp(6)))

        val btnVerifyIpz = MaterialButton(this).apply {
            text = getString(R.string.elec_verify_ipz)
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { verifyIpz() }
        }
        ipzLayout.addView(btnVerifyIpz, verticalParams(dp(8)))

        tvIpzResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        ipzLayout.addView(tvIpzResult)
        ipzCard.addView(ipzLayout)
        root.addView(ipzCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 9. LOAD CURRENT & BREAKER SIZING
        val loadCard = card()
        val loadLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loadLayout.addView(text("🔌 ${getString(R.string.elec_load_title)}", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        etLoadPower = inputField("Moc urządzenia (W) [np. 2500]")
        spinnerPhase = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1-Faza (230V)", "3-Fazy (400V)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        etLoadCosPhi = inputField("Współczynnik mocy cosφ [np. 0.95]")
        etLoadCosPhi.setText("0.95")

        loadLayout.addView(etLoadPower, verticalParams(dp(4)))
        loadLayout.addView(spinnerPhase, verticalParams(dp(6)))
        loadLayout.addView(etLoadCosPhi, verticalParams(dp(6)))

        val btnCalcLoad = MaterialButton(this).apply {
            text = getString(R.string.elec_calc_load)
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { calculateLoadAndBreaker() }
        }
        loadLayout.addView(btnCalcLoad, verticalParams(dp(8)))

        tvLoadResult = text("Wynik: Wpisz dane", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        loadLayout.addView(tvLoadResult)
        loadCard.addView(loadLayout)
        root.addView(loadCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        root.addView(buildLuxCalculatorCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        root.addView(buildRj45Card(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun buildLuxCalculatorCard(): MaterialCardView {
        val luxCard = card()
        val luxLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        luxLayout.addView(text("💡 Kalkulator Oświetlenia (Wprost & Odwrotny)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        val spinnerCalcMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "1. Wymagana Liczba Lamp (Dla zadanego Luxu)",
                "2. Odwrotny Kalkulator: Ile Luxów uzyskam z moich lamp?"
            ))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        val etRoomLength = inputField("Długość pomieszczenia L (m) [np. 5.0]")
        etRoomLength.setText("5.0")
        val etRoomWidth = inputField("Szerokość pomieszczenia W (m) [np. 4.0]")
        etRoomWidth.setText("4.0")
        val etRoomHeight = inputField("Wysokość pomieszczenia H (m) [np. 2.8]")
        etRoomHeight.setText("2.8")

        val spinnerTargetLux = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "Biuro / Gabinet (500 lx)",
                "Salon / Pokój dzienny (300 lx)",
                "Kuchnia / Łazienka (400 lx)",
                "Garaż / Piwnica / Magazyn (200 lx)",
                "Korytarz / Schody (100 lx)"
            ))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        val etLampCount = inputField("Liczba posiadanych/zamontowanych lamp (szt.) [np. 4]")
        etLampCount.setText("4")
        etLampCount.visibility = View.GONE

        spinnerCalcMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    spinnerTargetLux.visibility = View.VISIBLE
                    etLampCount.visibility = View.GONE
                } else {
                    spinnerTargetLux.visibility = View.GONE
                    etLampCount.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val spinnerLightType = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "LED (~100 lm/W)",
                "Świetlówka / Compact (~75 lm/W)",
                "Żarowa / Halogen (~14 lm/W)"
            ))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        val spinnerInputMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "Moc jednej lampy w Watach (W)",
                "Strumień świetlny jednej lampy w Lumenach (lm)"
            ))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        val etLampVal = inputField("Wartość dla 1 lampy (np. 15W lub 1500lm)")
        etLampVal.setText("15")

        val tvLuxResult = text("Wynik: Wpisz dane pomieszczenia", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }

        val btnCalcLux = MaterialButton(this).apply {
            text = "Oblicz Parametry Oświetlenia"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                val l = etRoomLength.text.toString().toDoubleOrNull() ?: 5.0
                val w = etRoomWidth.text.toString().toDoubleOrNull() ?: 4.0
                val area = l * w

                val efficacy = when (spinnerLightType.selectedItemPosition) {
                    0 -> 100.0
                    1 -> 75.0
                    else -> 14.0
                }

                val inputVal = etLampVal.text.toString().toDoubleOrNull() ?: 15.0
                val isByLumens = spinnerInputMode.selectedItemPosition == 1

                val lampLumens = if (isByLumens) inputVal else inputVal * efficacy
                val lampWatts = if (isByLumens) inputVal / efficacy else inputVal

                if (spinnerCalcMode.selectedItemPosition == 0) {
                    // MODE 1: Target Lux -> Required Lamps
                    val targetLux = when (spinnerTargetLux.selectedItemPosition) {
                        0 -> 500.0
                        1 -> 300.0
                        2 -> 400.0
                        3 -> 200.0
                        else -> 100.0
                    }
                    val totalLumensNeeded = (targetLux * area) / (0.65 * 0.8)
                    val requiredLampsCount = Math.ceil(totalLumensNeeded / lampLumens).toInt().coerceAtLeast(1)
                    val totalWattage = requiredLampsCount * lampWatts

                    tvLuxResult.text = String.format(
                        java.util.Locale.getDefault(),
                        "Powierzchnia: %.1f m² | Wymagane Lumeny: %.0f lm\nPotrzeba lamp: %d szt. (Łączna moc: %.0f W)",
                        area, totalLumensNeeded, requiredLampsCount, totalWattage
                    )
                } else {
                    // MODE 2: Inverse Calculator (Lamp count + specs -> Calculated Luxes)
                    val count = etLampCount.text.toString().toIntOrNull() ?: 4
                    val totalLumens = count * lampLumens
                    val estimatedLux = (totalLumens * 0.65 * 0.8) / area

                    val normEval = when {
                        estimatedLux >= 500 -> "Idealne do biura / pracy precyzyjnej (PN-EN 12464-1)"
                        estimatedLux >= 300 -> "Odpowiednie do salonu, kuchni, pracowni"
                        estimatedLux >= 200 -> "Odpowiednie do garażu, magazynu, łazienki"
                        else -> "Oświetlenie orientacyjne / korytarzowe"
                    }

                    tvLuxResult.text = String.format(
                        java.util.Locale.getDefault(),
                        "Powierzchnia: %.1f m² | Osiągalne natężenie: %.0f lx\nOcena: %s (Łącznie: %.0f lm, %.0f W)",
                        area, estimatedLux, normEval, totalLumens, count * lampWatts
                    )
                }
                tvLuxResult.setTextColor(0xFF43C59E.toInt())
            }
        }

        luxLayout.addView(spinnerCalcMode, verticalParams(dp(4)))
        luxLayout.addView(etRoomLength, verticalParams(dp(4)))
        luxLayout.addView(etRoomWidth, verticalParams(dp(4)))
        luxLayout.addView(etRoomHeight, verticalParams(dp(4)))
        luxLayout.addView(spinnerTargetLux, verticalParams(dp(6)))
        luxLayout.addView(etLampCount, verticalParams(dp(4)))
        luxLayout.addView(spinnerLightType, verticalParams(dp(6)))
        luxLayout.addView(spinnerInputMode, verticalParams(dp(6)))
        luxLayout.addView(etLampVal, verticalParams(dp(4)))
        luxLayout.addView(btnCalcLux, verticalParams(dp(8)))
        luxLayout.addView(tvLuxResult)

        luxCard.addView(luxLayout)
        return luxCard
    }

    private fun buildRj45Card(): MaterialCardView {
        val rj45Card = card()
        val rj45Layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        val titleText = text("🔌 Opis Wtyczki RJ45 (Standard T568B - Krok po Kroku)", 16f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(4))
            setOnClickListener { showRj45ImageDialog() }
        }
        rj45Layout.addView(titleText)

        val btnOpenImage = MaterialButton(this).apply {
            text = "📷 Kliknij, aby otworzyć pełny schemat graficzny (Obrazek)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(0xFF43C59E.toInt())
            setOnClickListener { showRj45ImageDialog() }
        }
        rj45Layout.addView(btnOpenImage, verticalParams(dp(4)))

        rj45Layout.addView(text("Widok od lewej do prawej strony wtyku (zatrzask spodem do dołu):", 12f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, dp(8)) })

        val pins = listOf(
            Triple("Pin 1 (TX+)", "Biało-Pomarańczowy", 0xFFFF8C00.toInt()),
            Triple("Pin 2 (TX-)", "Pomarańczowy", 0xFFE65100.toInt()),
            Triple("Pin 3 (RX+)", "Biało-Zielony", 0xFF4CAF50.toInt()),
            Triple("Pin 4", "Niebieski", 0xFF1976D2.toInt()),
            Triple("Pin 5", "Biało-Niebieski", 0xFF2196F3.toInt()),
            Triple("Pin 6 (RX-)", "Zielony", 0xFF2E7D32.toInt()),
            Triple("Pin 7", "Biało-Brązowy", 0xFF8D6E63.toInt()),
            Triple("Pin 8", "Brązowy", 0xFF4E342E.toInt())
        )

        pins.forEachIndexed { index, (pinName, colorName, colorHex) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = ContextCompat.getDrawable(this@ElectricianWorkbenchActivity, R.drawable.button_glass_bg)
            }

            val badge = TextView(this).apply {
                text = "${index + 1}"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundColor(0xFF283545.toInt())
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(24), dp(24)))

            val colorBar = View(this).apply {
                setBackgroundColor(colorHex)
            }
            row.addView(colorBar, LinearLayout.LayoutParams(dp(16), dp(20)).apply { marginStart = dp(8); marginEnd = dp(8) })

            val pinLabel = text("$pinName: $colorName", 13f, Color.WHITE, true)
            row.addView(pinLabel, LinearLayout.LayoutParams(0, -2, 1f))

            rj45Layout.addView(row, verticalParams(dp(4)))
        }

        rj45Layout.addView(text("💡 Wskazówka: Standard T568B jest powszechnie stosowany w Europie do budowy sieci LAN i okablowania strukturalnego.", 11f, 0xFF43C59E.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        rj45Card.addView(rj45Layout)
        return rj45Card
    }

    private fun showRj45ImageDialog() {
        val imageUrl = "https://media.cdn.kaufland.de/product-images/2048x2048/b317f05d3e2eed1fb0e0cc28e115116e.webp"
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
        }

        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        dialogView.addView(progressBar)
        dialogView.addView(imageView, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(this)
            .setTitle("🖼️ Graficzny Schemat Wtyku RJ45")
            .setView(dialogView)
            .setPositiveButton("Otwórz w przeglądarce") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(imageUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Nie można otworzyć przeglądarki", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Zamknij", null)
            .create()

        dialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                val conn = java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this@ElectricianWorkbenchActivity, "Nie udało się pobrać obrazka. Użyj przycisku przeglądarki.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildBusbarCard(): MaterialCardView {
        val busbarCard = card()
        val busbarLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        busbarLayout.addView(text("📏 Rozmierzanie Szynoprzewodów / Linii", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        val etBusbarLength = inputField("Długość trasy L (m) [np. 4.0]")

        val spinnerMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricianWorkbenchActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Tryb 1: Podaj liczbę punktów (oblicz odstęp)", "Tryb 2: Podaj żądany odstęp (oblicz liczbę punktów)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        val etBusbarCount = inputField("Liczba punktów / zawieszeń (szt.) [np. 4]")
        val etBusbarSpacing = inputField("Żądany odstęp / skok (cm) [np. 100.0]").apply { visibility = View.GONE }

        val etEdgeStart = inputField("Od startu (cm) [np. 15]")
        etEdgeStart.setText("15")
        val etEdgeEnd = inputField("Od końca (cm) [np. 15]")
        etEdgeEnd.setText("15")

        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    etBusbarCount.visibility = View.VISIBLE
                    etBusbarSpacing.visibility = View.GONE
                } else {
                    etBusbarCount.visibility = View.GONE
                    etBusbarSpacing.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        busbarLayout.addView(etBusbarLength, verticalParams(dp(4)))
        busbarLayout.addView(spinnerMode, verticalParams(dp(4)))
        busbarLayout.addView(etBusbarCount, verticalParams(dp(4)))
        busbarLayout.addView(etBusbarSpacing, verticalParams(dp(4)))
        busbarLayout.addView(etEdgeStart, verticalParams(dp(4)))
        busbarLayout.addView(etEdgeEnd, verticalParams(dp(4)))

        val tvBusbarResult = text("Wynik: Wpisz parametry trasy", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(8), 0, 0) }

        val btnCalcBusbar = MaterialButton(this).apply {
            text = "Oblicz Rozstaw i Liczbę Punktów"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                val l = etBusbarLength.text.toString().toDoubleOrNull()
                val edgeStart = etEdgeStart.text.toString().toDoubleOrNull() ?: 0.0
                val edgeEnd = etEdgeEnd.text.toString().toDoubleOrNull() ?: 0.0
                val mode = spinnerMode.selectedItemPosition

                if (l == null || l <= 0.0) {
                    tvBusbarResult.text = "Wprowadź prawidłową długość trasy!"
                    tvBusbarResult.setTextColor(0xFFE53935.toInt())
                    return@setOnClickListener
                }

                val totalCm = l * 100.0
                val usableSpan = totalCm - (edgeStart + edgeEnd)

                if (usableSpan < 0.0) {
                    tvBusbarResult.text = "Błąd: Suma odstępów od krawędzi jest większa niż długość trasy!"
                    tvBusbarResult.setTextColor(0xFFE53935.toInt())
                    return@setOnClickListener
                }

                val n: Int
                val step: Double

                if (mode == 0) {
                    val countVal = etBusbarCount.text.toString().toIntOrNull()
                    if (countVal == null || countVal <= 0) {
                        tvBusbarResult.text = "Wprowadź liczbę punktów!"
                        tvBusbarResult.setTextColor(0xFFE53935.toInt())
                        return@setOnClickListener
                    }
                    n = countVal
                    step = if (n > 1) usableSpan / (n - 1) else 0.0
                } else {
                    val desiredStep = etBusbarSpacing.text.toString().toDoubleOrNull()
                    if (desiredStep == null || desiredStep <= 0.0) {
                        tvBusbarResult.text = "Wprowadź żądany odstęp (cm)!"
                        tvBusbarResult.setTextColor(0xFFE53935.toInt())
                        return@setOnClickListener
                    }
                    val intervals = Math.round(usableSpan / desiredStep).toInt().coerceAtLeast(1)
                    n = intervals + 1
                    step = if (n > 1) usableSpan / (n - 1) else 0.0
                }

                val sb = StringBuilder()
                sb.append(String.format(Locale.getDefault(), "📏 TRASA: %.2f m (%.0f cm) | Wyliczono: %d punktów\n", l, totalCm, n))
                sb.append(String.format(Locale.getDefault(), "• Start: %.0f cm | Koniec: %.0f cm | Skok: %.1f cm\n\n", edgeStart, edgeEnd, step))

                if (n == 1) {
                    sb.append("• 1 punkt na środku trasy: %.1f cm od początku\n".format(edgeStart + usableSpan / 2.0))
                } else {
                    for (i in 0 until n) {
                        val posCm = edgeStart + (i * step)
                        if (i == 0) {
                            sb.append(String.format(Locale.getDefault(), "• Punkt 1: %.1f cm od początku (Start)\n", posCm))
                        } else if (i == n - 1) {
                            sb.append(String.format(Locale.getDefault(), "• Punkt %d: %.1f cm od początku (%.0f cm od końca)\n", i + 1, posCm, edgeEnd))
                        } else {
                            sb.append(String.format(Locale.getDefault(), "• Punkt %d: %.1f cm od początku (+%.1f cm)\n", i + 1, posCm, step))
                        }
                    }
                }

                tvBusbarResult.text = sb.toString()
                tvBusbarResult.setTextColor(0xFF43C59E.toInt())
            }
        }
        busbarLayout.addView(btnCalcBusbar, verticalParams(dp(8)))
        busbarLayout.addView(tvBusbarResult)
        busbarCard.addView(busbarLayout)
        return busbarCard
    }

    private fun calculateOhm() {
        val u = etOhmVolt.text.toString().toDoubleOrNull()
        val i = etOhmAmp.text.toString().toDoubleOrNull()
        val r = etOhmRes.text.toString().toDoubleOrNull()
        val p = etOhmPower.text.toString().toDoubleOrNull()
        val isAc = spinnerOhmType.selectedItemPosition == 1
        val cosPhi = if (isAc) (etOhmCosPhi.text.toString().toDoubleOrNull() ?: 1.0) else 1.0

        val resText = when {
            u != null && i != null -> {
                val resR = if (i != 0.0) u / i else 0.0
                val resP = u * i * cosPhi
                String.format(Locale.getDefault(), "R = %.2f Ω | P = %.2f W%s", resR, resP, if (isAc) " (Czynna przy cosφ=$cosPhi)" else "")
            }
            u != null && r != null -> {
                val resI = if (r != 0.0) u / r else 0.0
                val resP = (u * u) / r * cosPhi
                String.format(Locale.getDefault(), "I = %.2f A | P = %.2f W%s", resI, resP, if (isAc) " (Czynna przy cosφ=$cosPhi)" else "")
            }
            i != null && r != null -> {
                val resU = i * r
                val resP = i * i * r * cosPhi
                String.format(Locale.getDefault(), "U = %.2f V | P = %.2f W%s", resU, resP, if (isAc) " (Czynna przy cosφ=$cosPhi)" else "")
            }
            u != null && p != null -> {
                val effectiveU = u * cosPhi
                val resI = if (effectiveU != 0.0) p / effectiveU else 0.0
                val resR = if (resI != 0.0) u / resI else 0.0
                String.format(Locale.getDefault(), "I = %.2f A | R = %.2f Ω", resI, resR)
            }
            else -> "Wprowadź dokładnie 2 parametry!"
        }
        tvOhmResult.text = "Wynik: $resText"
        tvOhmResult.setTextColor(0xFF43C59E.toInt())
    }

    private fun calculateScale() {
        val selectedIdx = spinnerScale.selectedItemPosition
        val kFactor = when (selectedIdx) {
            0 -> 50.0
            1 -> 100.0
            2 -> 20.0
            3 -> 25.0
            4 -> 200.0
            5 -> 500.0
            6 -> 1.0
            else -> etScaleCustom.text.toString().toDoubleOrNull() ?: 50.0
        }

        val paperCm = etScalePaperCm.text.toString().toDoubleOrNull()
        val realMeters = etScaleRealMeters.text.toString().toDoubleOrNull()

        if (paperCm != null && paperCm > 0.0) {
            val calcRealMeters = (paperCm * kFactor) / 100.0
            val calcRealCm = paperCm * kFactor
            tvScaleResult.text = String.format(Locale.getDefault(), "Wymiar z planu %.1f cm przy skali 1:%.0f = %.2f m (%.0f cm w terenie)", paperCm, kFactor, calcRealMeters, calcRealCm)
            tvScaleResult.setTextColor(0xFF43C59E.toInt())
        } else if (realMeters != null && realMeters > 0.0) {
            val calcPaperCm = (realMeters * 100.0) / kFactor
            tvScaleResult.text = String.format(Locale.getDefault(), "Długość %.2f m w terenie przy skali 1:%.0f = %.2f cm na planie", realMeters, kFactor, calcPaperCm)
            tvScaleResult.setTextColor(0xFF43C59E.toInt())
        } else {
            tvScaleResult.text = "Wpisz wymiar na planie (cm) LUB w rzeczywistości (m)!"
            tvScaleResult.setTextColor(0xFFE53935.toInt())
        }
    }

    private fun calculateLightingInstaller() {
        val length = etLightLength.text.toString().toDoubleOrNull()
        val width = etLightWidth.text.toString().toDoubleOrNull()
        val count = etLightCount.text.toString().toIntOrNull()
        val isLineMode = spinnerLightLayout.selectedItemPosition == 1

        if (length == null || width == null || count == null || length <= 0.0 || width <= 0.0 || count <= 0) {
            tvLightResult.text = "Wprowadź poprawne wymiary (L, W) i liczbę opraw!"
            tvLightResult.setTextColor(0xFFE53935.toInt())
            diagramContainer.visibility = View.GONE
            return
        }

        val lengthCm = length * 100.0
        val widthCm = width * 100.0

        val nW: Int
        val nL: Int

        if (isLineMode) {
            nL = count
            nW = 1
        } else {
            val ratio = length / width
            nW = Math.round(Math.sqrt(count / ratio)).toInt().coerceAtLeast(1)
            nL = Math.round(count.toDouble() / nW).toInt().coerceAtLeast(1)
        }

        val totalGrid = nL * nW

        val dL = lengthCm / nL
        val aL = dL / 2.0

        val dW = widthCm / nW
        val aW = dW / 2.0

        val sb = StringBuilder()
        sb.append(String.format(Locale.getDefault(), "📐 SIATKA MONTAŻOWA: %d × %d OPRAW (%d szt. ogółem)\n\n", nL, nW, totalGrid))

        sb.append(String.format(Locale.getDefault(), "─── OŚ 1: DŁUGOŚĆ L (%.2f m / %.0f cm) ───\n", length, lengthCm))
        sb.append(String.format(Locale.getDefault(), "• Rozstaw między lampami: %.0f cm\n", dL))
        for (i in 1..nL) {
            val tapePos = aL + (i - 1) * dL
            if (i == 1) {
                sb.append(String.format(Locale.getDefault(), "• Lampa L1: %.0f cm od ściany A [Miarka: %.0f cm]\n", aL, tapePos))
            } else {
                sb.append(String.format(Locale.getDefault(), "• Lampa L%d: +%.0f cm [Miarka: %.0f cm]\n", i, dL, tapePos))
            }
        }
        sb.append(String.format(Locale.getDefault(), "(Od L%d do ściany B: %.0f cm)\n\n", nL, aL))

        sb.append(String.format(Locale.getDefault(), "─── OŚ 2: SZEROKOŚĆ W (%.2f m / %.0f cm) ───\n", width, widthCm))
        sb.append(String.format(Locale.getDefault(), "• Rozstaw między lampami: %.0f cm\n", dW))
        for (j in 1..nW) {
            val tapePos = aW + (j - 1) * dW
            if (j == 1) {
                sb.append(String.format(Locale.getDefault(), "• Lampa W1: %.0f cm od ściany C [Miarka: %.0f cm]\n", aW, tapePos))
            } else {
                sb.append(String.format(Locale.getDefault(), "• Lampa W%d: +%.0f cm [Miarka: %.0f cm]\n", j, dW, tapePos))
            }
        }
        sb.append(String.format(Locale.getDefault(), "(Od W%d do ściany D: %.0f cm)", nW, aW))

        tvLightResult.text = sb.toString()
        tvLightResult.setTextColor(0xFF43C59E.toInt())

        diagramContainer.removeAllViews()
        val diagram = CeilingDiagramView(this).apply {
            this.roomLength = length
            this.roomWidth = width
            this.nL = nL
            this.nW = nW
        }
        diagramContainer.addView(diagram, FrameLayout.LayoutParams(-1, -1))
        diagramContainer.visibility = View.VISIBLE
    }

    private fun calculateCableSizing() {
        val ib = etCableCurrent.text.toString().toDoubleOrNull()
        val l = etCableLength.text.toString().toDoubleOrNull() ?: 10.0
        val isAlu = spinnerCableMat.selectedItemPosition == 1
        val isThreePhase = spinnerCablePhase.selectedItemPosition == 1

        if (ib == null || ib <= 0.0) {
            tvCableResult.text = "Wprowadź prąd obciążenia Ib!"
            tvCableResult.setTextColor(0xFFE53935.toInt())
            return
        }

        val minSectionByCurrent = when {
            ib <= 14.5 -> 1.5
            ib <= 19.5 -> 2.5
            ib <= 26.0 -> 4.0
            ib <= 34.0 -> 6.0
            ib <= 46.0 -> 10.0
            ib <= 61.0 -> 16.0
            ib <= 80.0 -> 25.0
            else -> 35.0
        }

        val suggestedSection = if (isAlu) Math.max(minSectionByCurrent * 1.5, 4.0) else minSectionByCurrent

        val rho = if (isAlu) 0.028 else 0.0178
        val kFactor = if (isThreePhase) 1.732 else 2.0
        val vNom = if (isThreePhase) 400.0 else 230.0
        val deltaU = (kFactor * l * ib * rho) / suggestedSection
        val dropPercent = (deltaU / vNom) * 100.0

        val cableType = if (isThreePhase) {
            if (isAlu) "YAKXS 5x%.1f mm²".format(suggestedSection) else "YDY 5x%.1f mm²".format(suggestedSection)
        } else {
            if (isAlu) "YAKXS 3x%.1f mm²".format(suggestedSection) else "YDYp 3x%.1f mm²".format(suggestedSection)
        }

        tvCableResult.text = String.format(
            Locale.getDefault(),
            "Zalecany przekrój: %.1f mm² (%s)\nTyp: %s | ΔU = %.2f V (%.2f%%)",
            suggestedSection,
            if (isAlu) "Aluminium" else "Miedź",
            cableType,
            deltaU,
            dropPercent
        )
        tvCableResult.setTextColor(0xFF43C59E.toInt())
    }

    private fun calculateVoltageDrop() {
        val ib = etDropCurrent.text.toString().toDoubleOrNull()
        val l = etDropLength.text.toString().toDoubleOrNull()
        val s = etDropArea.text.toString().toDoubleOrNull()

        if (ib == null || l == null || s == null || s == 0.0) {
            tvDropResult.text = "Wprowadź poprawne dane!"
            tvDropResult.setTextColor(0xFFE53935.toInt())
            return
        }

        val deltaU = (2.0 * l * ib * 0.0178) / s
        val percent = (deltaU / 230.0) * 100.0

        val pass = percent <= 3.0
        tvDropResult.text = String.format(Locale.getDefault(), "ΔU = %.2f V (%.2f%%) -> %s", deltaU, percent, if (pass) "OK (≤ 3%)" else "PRZEKROCZENIE (> 3%)")
        tvDropResult.setTextColor(if (pass) 0xFF43C59E.toInt() else 0xFFE53935.toInt())
    }

    private fun verifyIpz() {
        val u0 = etIpzVoltage.text.toString().toDoubleOrNull() ?: 230.0
        val zs = etIpzZs.text.toString().toDoubleOrNull()

        if (zs == null || zs == 0.0) {
            tvIpzResult.text = "Wprowadź zmierzoną impedancję Zs!"
            tvIpzResult.setTextColor(0xFFE53935.toInt())
            return
        }

        val maxZsMap = mapOf(
            0 to 4.60,
            1 to 2.87,
            2 to 2.30,
            3 to 2.30,
            4 to 1.43,
            5 to 1.15
        )
        val selectedIdx = spinnerBreaker.selectedItemPosition
        val maxZs = maxZsMap[selectedIdx] ?: 2.87

        val calculatedIa = u0 / zs
        val pass = zs <= maxZs

        tvIpzResult.text = String.format(Locale.getDefault(), "Prąd zwarciowy Ia = %.1f A | Zs = %.2f Ω (Limit: %.2f Ω) -> %s", calculatedIa, zs, maxZs, if (pass) "OCHRONA SKUTECZNA (OK)" else "BRAK OCHRONY (ZA DUŻE Zs)")
        tvIpzResult.setTextColor(if (pass) 0xFF43C59E.toInt() else 0xFFE53935.toInt())
    }

    private fun calculateLoadAndBreaker() {
        val pW = etLoadPower.text.toString().toDoubleOrNull()
        val cosPhi = etLoadCosPhi.text.toString().toDoubleOrNull() ?: 0.95
        val isThreePhase = spinnerPhase.selectedItemPosition == 1

        if (pW == null || pW <= 0.0) {
            tvLoadResult.text = "Wprowadź moc urządzenia w Watach (W)!"
            tvLoadResult.setTextColor(0xFFE53935.toInt())
            return
        }

        val ib = if (isThreePhase) {
            pW / (Math.sqrt(3.0) * 400.0 * cosPhi)
        } else {
            pW / (230.0 * cosPhi)
        }

        val recommendedBreaker = when {
            ib <= 6.0 -> "B6 (In = 6A)"
            ib <= 10.0 -> "B10 (In = 10A)"
            ib <= 16.0 -> "B16 (In = 16A)"
            ib <= 20.0 -> "B20 (In = 20A)"
            ib <= 25.0 -> "B25 (In = 25A)"
            ib <= 32.0 -> "B32 (In = 32A)"
            else -> "C40 / Wyższe (In > 32A)"
        }

        tvLoadResult.text = String.format(Locale.getDefault(), "Prąd roboczy Ib = %.2f A -> Sugerowane zabezpieczenie: %s", ib, recommendedBreaker)
        tvLoadResult.setTextColor(0xFF43C59E.toInt())
    }

    private fun saveDiagramAsPng() {
        if (diagramContainer.childCount == 0 || diagramContainer.visibility != View.VISIBLE) {
            Toast.makeText(this, "Najpierw oblicz i wygeneruj rzut 2D!", Toast.LENGTH_SHORT).show()
            return
        }

        val view = diagramContainer.getChildAt(0) ?: return
        if (view.width <= 0 || view.height <= 0) {
            Toast.makeText(this, "Rzut 2D nie jest jeszcze w pełni widoczny!", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "rzut_opraw_$timestamp.png"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Pomiary")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ElectricianWorkbenchActivity, "Zapisano rzut 2D w Obrazy/Pomiary:\n$fileName", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Pomiary")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                java.io.FileOutputStream(file).use { os ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ElectricianWorkbenchActivity, "Zapisano rzut 2D w Obrazy/Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (err: Exception) {
                try {
                    val fallbackDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Pomiary").apply { if (!exists()) mkdirs() }
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val file = File(fallbackDir, "rzut_opraw_$timestamp.png")
                    java.io.FileOutputStream(file).use { os ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ElectricianWorkbenchActivity, "Zapisano w folderze Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ElectricianWorkbenchActivity, "Błąd zapisu grafiki: ${ex.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun inputField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF8290A2.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.button_glass_bg)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF9EADBD.toInt())
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
