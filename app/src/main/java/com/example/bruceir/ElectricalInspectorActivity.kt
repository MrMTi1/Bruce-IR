package com.example.bruceir

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ElectricalInspectorActivity : AppCompatActivity() {

    private class MeasurementRow(
        var circuitName: String,
        var breakerIndex: Int,
        var measuredZs: Double
    )

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

    private val rows = mutableListOf<MeasurementRow>()
    private lateinit var scrollMain: ScrollView
    private lateinit var containerRows: LinearLayout
    private lateinit var btnContinuousVoice: MaterialButton
    private lateinit var spinnerVoiceTimeout: Spinner
    private lateinit var cbFilterMax10: CheckBox
    private lateinit var cbAudioBeeps: CheckBox
    private lateinit var cbVibrate: CheckBox
    private val breakerOptions = listOf(
        "B6 (7.67Ω)", "B10 (4.60Ω)", "B16 (2.87Ω)", "B20 (2.30Ω)", "B25 (1.84Ω)", "B32 (1.44Ω)", "B40 (1.15Ω)", "B50 (0.92Ω)", "B63 (0.73Ω)",
        "C6 (3.83Ω)", "C10 (2.30Ω)", "C16 (1.44Ω)", "C20 (1.15Ω)", "C25 (0.92Ω)", "C32 (0.72Ω)", "C40 (0.58Ω)", "C50 (0.46Ω)", "C63 (0.37Ω)",
        "D6 (1.92Ω)", "D10 (1.15Ω)", "D16 (0.72Ω)", "D20 (0.58Ω)", "D25 (0.46Ω)", "D32 (0.36Ω)", "D40 (0.29Ω)", "D50 (0.23Ω)", "D63 (0.18Ω)"
    )
    private val maxZsValues = listOf(
        7.67, 4.60, 2.87, 2.30, 1.84, 1.44, 1.15, 0.92, 0.73,
        3.83, 2.30, 1.44, 1.15, 0.92, 0.72, 0.58, 0.46, 0.37,
        1.92, 1.15, 0.72, 0.58, 0.46, 0.36, 0.29, 0.23, 0.18
    )

    private var toneGenerator: ToneGenerator? = null
    private var activeVoiceRowIndex = -1
    private var isContinuousVoiceActive = false
    private var continuousSilenceStartMs: Long = 0L

    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingCsvContent: String = ""
    private var wakeLock: PowerManager.WakeLock? = null

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startInAppSpeechListening()
        } else {
            isContinuousVoiceActive = false
            updateContinuousButtonState()
            Toast.makeText(this, "Wymagane uprawnienie mikrofonu do dyktowania", Toast.LENGTH_SHORT).show()
        }
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(pendingCsvContent.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Zapisano protokół we wybranym miejscu!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Błąd zapisu: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.ELECTRICIAN)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {}

        buildScreen()
        initInAppSpeechRecognizer()
        addNewRow()

        // Mikrofon startuje tylko na jawne żądanie własnych komponentów (nie z obcego intentu).
        if (intent?.getBooleanExtra("AUTO_VOICE", false) == true) {
            toggleContinuousVoice()
        }
    }

    override fun onPause() {
        super.onPause()
        stopContinuousVoiceInternal()
    }

    override fun onDestroy() {
        stopContinuousVoiceInternal()
        releaseWakeLock()
        toneGenerator?.release()
        toneGenerator = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BruceIR::InspectorVoiceWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("BruceIR", "WakeLock acquire error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val hasData = rows.any { it.measuredZs > 0.0 }
        if (hasData) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Niezapisane Pomiary!")
                .setMessage("Masz wprowadzone wyniki pomiarów w protokole. Jeśli wyjdziesz bez zapisu, dane zostaną utracone.")
                .setPositiveButton("💾 ZAPISZ TERAZ") { _, _ ->
                    showSaveProtocolDialog()
                }
                .setNeutralButton("WYJDŹ BEZ ZAPISU") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("ANULUJ", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private fun initInAppSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            if (isContinuousVoiceActive) {
                                scheduleNextVoiceInput()
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spokenText = matches?.firstOrNull() ?: ""
                            handleVoiceResult(spokenText)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("BruceIR", "Error initializing speech recognizer: ${e.message}")
        }
    }

    private fun handleVoiceResult(spokenText: String) {
        val cleanLower = spokenText.lowercase(Locale.getDefault()).trim()

        if (cleanLower == "stop" || cleanLower == "koniec" || cleanLower == "zakończ" ||
            cleanLower == "zakoncz" || cleanLower == "zatrzymaj" || cleanLower == "pauza" || cleanLower == "przerwa") {
            stopContinuousVoiceInternal()
            playErrorBeep()
            Toast.makeText(this, "Zatrzymano dyktowanie głosowe (Pauza)", Toast.LENGTH_SHORT).show()
            return
        }

        if (cleanLower == "rozpocznij" || cleanLower == "start" || cleanLower == "zacznij" || cleanLower == "dyktuj") {
            if (!isContinuousVoiceActive) {
                toggleContinuousVoice()
                playSuccessBeep()
            }
            return
        }

        if (cleanLower == "zapisz" || cleanLower == "zapis" || cleanLower == "zapisuj" || cleanLower == "protokół" || cleanLower == "protokol") {
            playSuccessBeep()
            showSaveProtocolDialog()
            return
        }

        // Głosowa zmiana typu zabezpieczenia obwodu (np. "zmień na D16", "C16", "B10")
        val breakerIndexMatched = breakerOptions.indexOfFirst { option ->
            val code = option.substringBefore(" ").lowercase()
            val charType = code.substring(0, 1)
            val numValue = code.substring(1)
            cleanLower.contains(code) || cleanLower.contains("$charType $numValue")
        }

        if (breakerIndexMatched != -1) {
            val targetIdx = if (activeVoiceRowIndex in rows.indices) activeVoiceRowIndex else rows.lastIndex
            if (targetIdx in rows.indices) {
                rows[targetIdx].breakerIndex = breakerIndexMatched
                renderRows()
                playSuccessBeep()
                Toast.makeText(this, "⚡ Zmieniono zabezpieczenie na: ${breakerOptions[breakerIndexMatched]}", Toast.LENGTH_SHORT).show()
                continuousSilenceStartMs = System.currentTimeMillis()

                if (isContinuousVoiceActive) {
                    activeVoiceRowIndex = rows.lastIndex
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isContinuousVoiceActive) {
                            startInAppSpeechListening()
                        }
                    }, 400)
                }
                return
            }
        }

        if (activeVoiceRowIndex in rows.indices) {
            val parsedVal = parseSpokenMeasurement(spokenText)
            if (parsedVal > 0.0) {
                rows[activeVoiceRowIndex].measuredZs = parsedVal
                renderRows()
                checkAndAutoAddTrailingRow()
                continuousSilenceStartMs = System.currentTimeMillis()
                playSuccessBeep()
            } else {
                playErrorBeep()
                Toast.makeText(this, "Nie zrozumiano: '$spokenText'", Toast.LENGTH_SHORT).show()
            }

            if (isContinuousVoiceActive) {
                activeVoiceRowIndex = rows.lastIndex
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isContinuousVoiceActive) {
                        startInAppSpeechListening()
                    }
                }, 400)
            }
        }
    }

    private fun scheduleNextVoiceInput() {
        val timeoutMode = spinnerVoiceTimeout.selectedItemPosition
        val maxAllowedMs = when (timeoutMode) {
            1 -> 10000L
            2 -> 30000L
            3 -> 60000L
            4 -> 180000L
            else -> Long.MAX_VALUE
        }

        val elapsed = System.currentTimeMillis() - continuousSilenceStartMs
        if (elapsed > maxAllowedMs) {
            stopContinuousVoiceInternal()
            Toast.makeText(this, "Przekroczono limit ciszy. Zatrzymano dyktowanie.", Toast.LENGTH_LONG).show()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (isContinuousVoiceActive) {
                startInAppSpeechListening()
            }
        }, 400)
    }

    private fun startInAppSpeechListening() {
        if (!isContinuousVoiceActive && activeVoiceRowIndex < 0) return

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }

        try {
            if (speechRecognizer == null) {
                initInAppSpeechRecognizer()
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("BruceIR", "Speech listening error: ${e.message}")
            if (isContinuousVoiceActive) {
                scheduleNextVoiceInput()
            }
        }
    }

    private fun playSuccessBeep() {
        if (!::cbAudioBeeps.isInitialized || cbAudioBeeps.isChecked) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (_: Exception) {}
        }
        if (!::cbVibrate.isInitialized || cbVibrate.isChecked) {
            vibrateDevice(isError = false)
        }
    }

    private fun playErrorBeep() {
        if (!::cbAudioBeeps.isInitialized || cbAudioBeeps.isChecked) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
            } catch (_: Exception) {}
        }
        if (!::cbVibrate.isInitialized || cbVibrate.isChecked) {
            vibrateDevice(isError = true)
        }
    }

    private fun vibrateDevice(isError: Boolean) {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = if (isError) 255 else 180
                val duration = if (isError) 400L else 150L
                v?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(if (isError) 400L else 150L)
            }
        } catch (_: Exception) {}
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun scrollToBottom() {
        scrollMain.post {
            scrollMain.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun buildScreen() {
        scrollMain = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 15))
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(130))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollMain.addView(root)
        setContentView(scrollMain)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("INSPECTOR MODE", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Protokół Pomiary IPZ (Zs)", 28f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Narzędzia inżynierskie i protokół pomiarowy Zs.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        // 1. LIGHTING INSTALLER CALCULATOR CARD (TOP)
        val lightCard = card()
        val lightLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lightLayout.addView(text("💡 Rozmieszczenie Opraw Oświetleniowych (2D)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        val etLightLength = inputField("Długość pomieszczenia L (m) [np. 6.0]")
        val etLightWidth = inputField("Szerokość pomieszczenia W (m) [np. 4.0]")
        val etLightCount = inputField("Liczba opraw (szt.) [np. 6]")
        val spinnerLightLayout = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricalInspectorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Siatka / Prostokąt (Rzędy × Kolumny)", "W Jednej Linii (1 Rząd wzdłuż L)"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        lightLayout.addView(etLightLength, verticalParams(dp(4)))
        lightLayout.addView(etLightWidth, verticalParams(dp(4)))
        lightLayout.addView(etLightCount, verticalParams(dp(4)))
        lightLayout.addView(spinnerLightLayout, verticalParams(dp(6)))

        val tvLightResult = text("Wynik: Wpisz wymiary", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        val diagramContainer = FrameLayout(this).apply { visibility = View.GONE }

        val btnCalcLight = MaterialButton(this).apply {
            text = "Oblicz Rozstaw i Rysuj Rzut (cm)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                val length = etLightLength.text.toString().toDoubleOrNull()
                val width = etLightWidth.text.toString().toDoubleOrNull()
                val count = etLightCount.text.toString().toIntOrNull()
                val isLineMode = spinnerLightLayout.selectedItemPosition == 1

                if (length == null || width == null || count == null || length <= 0.0 || width <= 0.0 || count <= 0) {
                    tvLightResult.text = "Wprowadź poprawne wymiary (L, W) i liczbę opraw!"
                    tvLightResult.setTextColor(0xFFE53935.toInt())
                    diagramContainer.visibility = View.GONE
                    return@setOnClickListener
                }

                val lengthCm = length * 100.0
                val widthCm = width * 100.0
                val requestedCount = count
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

                if (!isLineMode && totalGrid != requestedCount) {
                    AlertDialog.Builder(this@ElectricalInspectorActivity)
                        .setTitle("💡 Dopasowanie siatki opraw")
                        .setMessage("Wpisana liczba opraw ($requestedCount) nie tworzy pełnej siatki prostokątnej.\n\nAutomatycznie dopasowano do: $totalGrid szt. ($nL rzędów × $nW kolumn).")
                        .setPositiveButton("OK", null)
                        .show()
                }

                val dL = lengthCm / nL
                val aL = dL / 2.0
                val dW = widthCm / nW
                val aW = dW / 2.0

                val sb = StringBuilder()
                if (!isLineMode && totalGrid != requestedCount) {
                    sb.append(String.format(Locale.getDefault(), "ℹ INFO: Dopasowano z %d do %d szt. (%d rzędów × %d kolumn)\n\n", requestedCount, totalGrid, nL, nW))
                }
                sb.append(String.format(Locale.getDefault(), "📐 SIATKA MONTAŻOWA: %d × %d OPRAW (%d szt. ogółem)\n\n", nL, nW, totalGrid))
                sb.append(String.format(Locale.getDefault(), "─── OŚ 1: DŁUGOŚĆ L (%.2f m / %.0f cm) ───\n• Rozstaw między lampami: %.0f cm\n", length, lengthCm, dL))
                for (i in 1..nL) {
                    val tapePos = aL + (i - 1) * dL
                    if (i == 1) {
                        sb.append(String.format(Locale.getDefault(), "• Lampa L1: %.0f cm od ściany [Miarka: %.0f cm]\n", aL, tapePos))
                    } else {
                        sb.append(String.format(Locale.getDefault(), "• Lampa L%d: +%.0f cm [Miarka: %.0f cm]\n", i, dL, tapePos))
                    }
                }
                sb.append(String.format(Locale.getDefault(), "\n─── OŚ 2: SZEROKOŚĆ W (%.2f m / %.0f cm) ───\n• Rozstaw między lampami: %.0f cm\n", width, widthCm, dW))
                for (j in 1..nW) {
                    val tapePos = aW + (j - 1) * dW
                    if (j == 1) {
                        sb.append(String.format(Locale.getDefault(), "• Lampa W1: %.0f cm od ściany [Miarka: %.0f cm]\n", aW, tapePos))
                    } else {
                        sb.append(String.format(Locale.getDefault(), "• Lampa W%d: +%.0f cm [Miarka: %.0f cm]\n", j, dW, tapePos))
                    }
                }

                tvLightResult.text = sb.toString()
                tvLightResult.setTextColor(0xFF43C59E.toInt())

                diagramContainer.removeAllViews()
                val diagram = CeilingDiagramView(this@ElectricalInspectorActivity).apply {
                    this.roomLength = length
                    this.roomWidth = width
                    this.nL = nL
                    this.nW = nW
                }
                diagramContainer.addView(diagram, FrameLayout.LayoutParams(-1, -1))
                diagramContainer.visibility = View.VISIBLE
            }
        }
        lightLayout.addView(btnCalcLight, verticalParams(dp(8)))

        val btnSaveDiagramPng = MaterialButton(this).apply {
            text = "🖼️ Zapisz Rzut 2D do Pliku (PNG)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { saveDiagramAsPng(diagramContainer) }
        }
        lightLayout.addView(btnSaveDiagramPng, verticalParams(dp(6)))

        lightLayout.addView(tvLightResult)
        lightLayout.addView(diagramContainer, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(10) })
        lightCard.addView(lightLayout)
        root.addView(lightCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. BUSBAR / TRACK LAYOUT CALCULATOR CARD (SECOND)
        val busbarCard = card()
        val busbarLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        busbarLayout.addView(text("📏 Rozmierzanie Szynoprzewodów / Linii", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        val etBusbarLength = inputField("Długość trasy L (m) [np. 4.0]")
        val etBusbarCount = inputField("Liczba punktów / zawieszeń (szt.) [np. 4]")
        val etBusbarEdge = inputField("Dystans od krawędzi (cm) [np. 15.0]")
        etBusbarEdge.setText("15")

        busbarLayout.addView(etBusbarLength, verticalParams(dp(4)))
        busbarLayout.addView(etBusbarCount, verticalParams(dp(4)))
        busbarLayout.addView(etBusbarEdge, verticalParams(dp(4)))

        val tvBusbarResult = text("Wynik: Wpisz parametry trasy", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(8), 0, 0) }

        val btnCalcBusbar = MaterialButton(this).apply {
            text = "Oblicz Rozstaw Punktów na Szynoprzewodzie"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                val l = etBusbarLength.text.toString().toDoubleOrNull()
                val n = etBusbarCount.text.toString().toIntOrNull()
                val edge = etBusbarEdge.text.toString().toDoubleOrNull() ?: 0.0

                if (l == null || n == null || l <= 0.0 || n <= 0) {
                    tvBusbarResult.text = "Wprowadź poprawne dane trasy!"
                    tvBusbarResult.setTextColor(0xFFE53935.toInt())
                    return@setOnClickListener
                }

                val totalCm = l * 100.0
                val usableSpan = totalCm - (2.0 * edge)

                if (usableSpan < 0.0) {
                    tvBusbarResult.text = "Błąd: Odstępy od krawędzi są większe niż długość trasy!"
                    tvBusbarResult.setTextColor(0xFFE53935.toInt())
                    return@setOnClickListener
                }

                val sb = StringBuilder()
                sb.append(String.format(Locale.getDefault(), "📏 TRASA: %.2f m (%.0f cm) | %d punktów | Krawędź: %.0f cm\n\n", l, totalCm, n, edge))

                if (n == 1) {
                    sb.append("• 1 punkt na środku trasy: %.1f cm od początku\n".format(totalCm / 2.0))
                } else {
                    val step = usableSpan / (n - 1)
                    sb.append(String.format(Locale.getDefault(), "• Odstęp między punktami (skok): %.1f cm\n\n", step))
                    for (i in 0 until n) {
                        val posCm = edge + (i * step)
                        if (i == 0) {
                            sb.append(String.format(Locale.getDefault(), "• Punkt 1: %.1f cm od początku (Start)\n", posCm))
                        } else if (i == n - 1) {
                            sb.append(String.format(Locale.getDefault(), "• Punkt %d: %.1f cm od początku (%.0f cm od końca)\n", i + 1, posCm, edge))
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
        root.addView(busbarCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 3. INSPECTOR / VOICE DICTATION CARD (THIRD)
        val actionCard = card()
        val actionLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionLayout.addView(text("📋 Tryb Pomiarowca Zs (Hands-Free)", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        btnContinuousVoice = MaterialButton(this).apply {
            text = "🎤 Start Dyktowania w Tle (Hands-Free)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener { toggleContinuousVoice() }
        }
        actionLayout.addView(btnContinuousVoice, verticalParams(dp(4)))

        val timeoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        timeoutRow.addView(label("Czekaj przy ciszy:"))
        spinnerVoiceTimeout = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricalInspectorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Bez limitu (Tylko komenda 'STOP')", "Limit 10 sekund", "Limit 30 sekund", "Limit 1 minuta", "Limit 3 minuty"))
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        timeoutRow.addView(spinnerVoiceTimeout, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        actionLayout.addView(timeoutRow)

        cbFilterMax10 = CheckBox(this).apply {
            text = "Filtruj zakłócenia > 10Ω (Odrzucaj błędy)"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        actionLayout.addView(cbFilterMax10, verticalParams(dp(6)))

        cbAudioBeeps = CheckBox(this).apply {
            text = "🔊 Głośne dźwięki potwierdzeń (Alarm stream)"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        actionLayout.addView(cbAudioBeeps, verticalParams(dp(6)))

        cbVibrate = CheckBox(this).apply {
            text = "📳 Wibracja hętkowa przy pomiarze"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        actionLayout.addView(cbVibrate, verticalParams(dp(6)))

        val btnAddRow = MaterialButton(this).apply {
            text = "+ Dodaj Nowy Obwód Pomiarowy"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { addNewRow() }
        }
        actionLayout.addView(btnAddRow, verticalParams(dp(6)))

        val btnSaveProtocol = MaterialButton(this).apply {
            text = "📁 Zapisz Protokół (Dokumenty / Pomiary)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { showSaveProtocolDialog() }
        }
        actionLayout.addView(btnSaveProtocol, verticalParams(dp(6)))

        actionCard.addView(actionLayout)
        root.addView(actionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        root.addView(text("Tabela Pomiarów Obwodów", 16f, Color.WHITE, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        containerRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(containerRows)
    }

    private fun saveDiagramAsPng(diagramContainer: FrameLayout) {
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
                            Toast.makeText(this@ElectricalInspectorActivity, "Zapisano rzut 2D w Obrazy/Pomiary:\n$fileName", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@ElectricalInspectorActivity, "Zapisano rzut 2D w Obrazy/Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@ElectricalInspectorActivity, "Zapisano w folderze Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ElectricalInspectorActivity, "Błąd zapisu grafiki: ${ex.message}", Toast.LENGTH_LONG).show()
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

    private fun toggleContinuousVoice() {
        isContinuousVoiceActive = !isContinuousVoiceActive
        updateContinuousButtonState()
        if (isContinuousVoiceActive) {
            acquireWakeLock()
            continuousSilenceStartMs = System.currentTimeMillis()
            activeVoiceRowIndex = if (rows.isNotEmpty() && rows.last().measuredZs == 0.0) rows.lastIndex else {
                addNewRow()
                rows.lastIndex
            }
            startInAppSpeechListening()
        } else {
            stopContinuousVoiceInternal()
        }
    }

    private fun stopContinuousVoiceInternal() {
        isContinuousVoiceActive = false
        releaseWakeLock()
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        updateContinuousButtonState()
    }

    private fun updateContinuousButtonState() {
        if (isContinuousVoiceActive) {
            btnContinuousVoice.text = "⏹ Zatrzymaj Dyktowanie (Słucham w Tle... 🎤)"
            btnContinuousVoice.setBackgroundColor(0xFFE53935.toInt())
            btnContinuousVoice.setTextColor(Color.WHITE)
        } else {
            btnContinuousVoice.text = "🎤 Start Dyktowania w Tle (Hands-Free)"
            btnContinuousVoice.setBackgroundColor(0xFF43C59E.toInt())
            btnContinuousVoice.setTextColor(Color.BLACK)
        }
    }

    private fun addNewRow() {
        val newId = rows.size + 1
        val inheritedBreaker = if (rows.isNotEmpty()) rows.last().breakerIndex else 1
        val row = MeasurementRow("Obwód #$newId", inheritedBreaker, 0.0)
        rows.add(row)
        containerRows.addView(createRowCard(rows.lastIndex, row), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        scrollToBottom()
    }

    private fun checkAndAutoAddTrailingRow() {
        if (rows.isNotEmpty()) {
            val lastRow = rows.last()
            if (lastRow.measuredZs > 0.0) {
                val nextIndex = rows.size
                val newRow = MeasurementRow("Obwód #${nextIndex + 1}", lastRow.breakerIndex, 0.0)
                rows.add(newRow)
                containerRows.addView(createRowCard(index = nextIndex, row = newRow), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                scrollToBottom()
            }
        }
    }

    private fun startVoiceInput(rowIndex: Int) {
        activeVoiceRowIndex = rowIndex
        startInAppSpeechListening()
    }

    private fun parseSpokenMeasurement(spoken: String): Double {
        val clean = spoken.lowercase(Locale.getDefault())
            .replace("przecinek", ".")
            .replace("koma", ".")
            .replace("punkt", ".")
            .replace("dot", ".")
            .replace(" ", "")
        return validateAndParseMeasurement(clean)
    }

    private fun validateAndParseMeasurement(text: String): Double {
        val parsed = parseAutoDecimal(text)
        if (cbFilterMax10.isChecked && parsed > 10.0) {
            playErrorBeep()
            Toast.makeText(this, "Odrzucono wynik %.2f Ω (> 10Ω - Filtr aktywny)".format(parsed), Toast.LENGTH_SHORT).show()
            return 0.0
        }
        return parsed
    }

    private fun parseAutoDecimal(text: String): Double {
        val rawText = text.trim().replace(',', '.')
        if (rawText.isEmpty()) return 0.0
        return if (rawText.contains('.')) {
            rawText.toDoubleOrNull() ?: 0.0
        } else {
            val intVal = rawText.toLongOrNull() ?: return 0.0
            if (rawText.length >= 3) intVal / 100.0 else intVal.toDouble()
        }
    }

    private fun renderRows() {
        try {
            currentFocus?.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Exception) {}

        containerRows.removeAllViews()
        rows.forEachIndexed { index, row ->
            containerRows.addView(createRowCard(index, row), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        scrollToBottom()
    }

    private fun createRowCard(index: Int, row: MeasurementRow): MaterialCardView {
        val card = card()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(text("#${index + 1}", 16f, 0xFF43C59E.toInt(), true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

        val etName = EditText(this).apply {
            setText(row.circuitName)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setTextSize(14f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundResource(R.drawable.button_glass_bg)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    row.circuitName = s.toString()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        topRow.addView(etName, LinearLayout.LayoutParams(0, -2, 1f))

        val btnDelete = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(0xFFFF5252.toInt())
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                if (rows.size > 1) {
                    rows.removeAt(index)
                    renderRows()
                } else {
                    Toast.makeText(this@ElectricalInspectorActivity, "Musi pozostać przynajmniej 1 obwód", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topRow.addView(btnDelete, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        layout.addView(topRow)

        val tvResult = text("", 13f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, 0)
        }

        val middleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        middleRow.addView(label("Zabezpieczenie:"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ElectricalInspectorActivity, android.R.layout.simple_spinner_dropdown_item, breakerOptions)
            setSelection(row.breakerIndex)
            setBackgroundResource(R.drawable.button_glass_bg)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    row.breakerIndex = position
                    updateRowResult(row, tvResult, playAudio = false)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        middleRow.addView(spinner, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        layout.addView(middleRow)

        val zsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        zsRow.addView(label("Zmierzona Zs (Ω):"))

        val etZs = EditText(this).apply {
            setText(if (row.measuredZs > 0.0) String.format(Locale.US, "%.2f", row.measuredZs) else "")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            setTextSize(14f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundResource(R.drawable.button_glass_bg)

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val parsed = validateAndParseMeasurement(s.toString())
                    row.measuredZs = parsed
                    updateRowResult(row, tvResult, playAudio = true)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && row.measuredZs > 0.0) {
                    val formatted = String.format(Locale.US, "%.2f", row.measuredZs)
                    if (text.toString() != formatted) {
                        setText(formatted)
                    }
                    if (index == rows.lastIndex) {
                        checkAndAutoAddTrailingRow()
                    }
                }
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    if (row.measuredZs > 0.0) {
                        val formatted = String.format(Locale.US, "%.2f", row.measuredZs)
                        if (text.toString() != formatted) {
                            setText(formatted)
                        }
                    }
                    if (index == rows.lastIndex && row.measuredZs > 0.0) {
                        checkAndAutoAddTrailingRow()
                    }
                }
                false
            }
        }
        zsRow.addView(etZs, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })

        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(0xFF43C59E.toInt())
            setBackgroundResource(R.drawable.button_glass_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                startVoiceInput(index)
            }
        }
        zsRow.addView(btnMic, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })

        layout.addView(zsRow)

        updateRowResult(row, tvResult, playAudio = false)
        layout.addView(tvResult)

        card.addView(layout)
        return card
    }

    private fun updateRowResult(row: MeasurementRow, tvResult: TextView, playAudio: Boolean = false) {
        val maxZs = maxZsValues.getOrElse(row.breakerIndex) { 2.87 }
        val pass = row.measuredZs > 0.0 && row.measuredZs <= maxZs
        val statusText = if (row.measuredZs == 0.0) {
            "Status: Wprowadź Zs (przygotowany)"
        } else if (pass) {
            if (playAudio) playSuccessBeep()
            String.format(Locale.getDefault(), "✔ POZYTYW (Zs = %.2f Ω ≤ %.2f Ω)", row.measuredZs, maxZs)
        } else {
            if (playAudio) playErrorBeep()
            String.format(Locale.getDefault(), "✖ NEGATYW (Zs = %.2f Ω > Limit %.2f Ω)", row.measuredZs, maxZs)
        }
        tvResult.text = statusText
        tvResult.setTextColor(if (row.measuredZs == 0.0) 0xFF9EADBD.toInt() else if (pass) 0xFF43C59E.toInt() else 0xFFE53935.toInt())
    }

    private fun generateCsvContent(): String {
        val validRows = rows.filter { it.measuredZs > 0.0 }
        val sb = StringBuilder()
        sb.append("Nr;Nazwa Obwodu;Zabezpieczenie;Zmierzona Zs (Ohm);Max Zs (Ohm);Wynik\n")
        validRows.forEachIndexed { idx, row ->
            val maxZs = maxZsValues.getOrElse(row.breakerIndex) { 2.87 }
            val pass = row.measuredZs > 0.0 && row.measuredZs <= maxZs
            val verdict = if (pass) "POZYTYW" else "NEGATYW"
            sb.append(String.format(Locale.getDefault(), "%d;%s;%s;%.2f;%.2f;%s\n", idx + 1, row.circuitName, breakerOptions[row.breakerIndex], row.measuredZs, maxZs, verdict))
        }
        return sb.toString()
    }

    private fun showSaveProtocolDialog() {
        val validRows = rows.filter { it.measuredZs > 0.0 }
        if (validRows.isEmpty()) {
            Toast.makeText(this, "Brak obwodów z wprowadzonym wynikiem Zs do zapisu!", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultName = "protokol_pomiary_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        val input = EditText(this).apply {
            setText(defaultName)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.button_glass_bg)
        }

        AlertDialog.Builder(this)
            .setTitle("Zapis Protokołu Pomiary Zs")
            .setMessage("Podaj nazwę pliku i wybierz docelową lokalizację zapisu:")
            .setView(input)
            .setPositiveButton("📁 Dokumenty/Pomiary/") { _, _ ->
                val fileName = input.text.toString().trim().ifEmpty { "protokol_pomiary" }
                saveProtocolToPomiaryFolder(fileName)
            }
            .setNeutralButton("📂 Wybierz Inny Folder") { _, _ ->
                val fileName = input.text.toString().trim().ifEmpty { "protokol_pomiary" }
                pendingCsvContent = generateCsvContent()
                val cleanName = if (fileName.endsWith(".csv", true)) fileName else "$fileName.csv"
                createDocumentLauncher.launch(cleanName)
            }
            .setNegativeButton("ANULUJ", null)
            .show()
    }

    private fun saveProtocolToPomiaryFolder(fileName: String) {
        val cleanName = if (fileName.endsWith(".csv", true)) fileName else "$fileName.csv"
        val csvText = generateCsvContent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Pomiary")
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(csvText.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "Zapisano protokół w Dokumenty/Pomiary:\n$cleanName", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (e: Exception) {
                Log.e("BruceIR", "MediaStore error: ${e.message}")
            }
        }

        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Pomiary")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, cleanName)
            file.writeText(csvText, Charsets.UTF_8)
            Toast.makeText(this, "Zapisano protokół w Dokumenty/Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            val fallbackDir = File(getExternalFilesDir(null), "Pomiary").apply { if (!exists()) mkdirs() }
            val file = File(fallbackDir, cleanName)
            file.writeText(csvText, Charsets.UTF_8)
            Toast.makeText(this, "Zapisano w folderze Pomiary:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
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
