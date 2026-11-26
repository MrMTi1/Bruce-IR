package com.example.bruceir

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RfAnalyzerActivity : AppCompatActivity() {

    private lateinit var tvConnBanner: TextView
    private lateinit var spinnerFreq: Spinner
    private lateinit var etCustomFreq: EditText
    private lateinit var btnStartRx: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvDecodedInfo: TextView
    private lateinit var etEditHex: EditText
    private lateinit var containerCaptures: LinearLayout

    private val captureResults = mutableListOf<RfProtocolDecoder.RfDecodeResult>()
    private var isReceiving = false
    private var isSweeping = false
    private val handler = Handler(Looper.getMainLooper())
    private val subChannels433 = listOf(433920000L, 433420000L, 433875000L, 434075000L, 434420000L, 434790000L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.WIRELESS)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)

        buildScreen()

        BruceBleRadioBridge.setListener(object : BruceBleRadioBridge.RadioBridgeListener {
            override fun onRfSignalReceived(result: RfProtocolDecoder.RfDecodeResult) {
                runOnUiThread {
                    captureResults.add(0, result)
                    updateDecodedUi(result)
                    renderCaptures()
                }
            }

            override fun onStatusChanged(statusText: String) {
                runOnUiThread {
                    tvStatus.text = "Status: $statusText"
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::tvConnBanner.isInitialized) {
            val connected = BruceBleManager.isConnected
            tvConnBanner.text = if (connected) "🟢 Połączono z Bruce/LilyGO przez BLE" else "⚠️ Brak połączenia z Bruce! [Kliknij, aby połączyć z BLE]"
            tvConnBanner.setTextColor(if (connected) 0xFF43C59E.toInt() else 0xFFFFD54F.toInt())
        }
    }

    override fun onDestroy() {
        stopSweeping()
        BruceBleRadioBridge.setListener(null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun getSelectedFrequencyHz(): Long {
        val pos = spinnerFreq.selectedItemPosition
        return when (pos) {
            0 -> 433920000L
            1 -> 433420000L
            2 -> 434075000L
            3 -> 434420000L
            4 -> 315000000L
            5 -> 868350000L
            else -> {
                val customMHz = etCustomFreq.text.toString().toDoubleOrNull() ?: 433.92
                (customMHz * 1000000.0).toLong()
            }
        }
    }

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
        titleStack.addView(text("RF SUB-GHZ ANALYZER", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Analizator & Edytor RF 433/868", 24f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Odbiór, dekodowanie protokołów, edycja HEX, re-transmisja i eksport .sub dla Flipper Zero.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        })

        tvConnBanner = text(
            if (BruceBleManager.isConnected) "🟢 Połączono z Bruce/LilyGO przez BLE" else "⚠️ Brak połączenia z Bruce! [Kliknij, aby połączyć z BLE]",
            13f,
            if (BruceBleManager.isConnected) 0xFF43C59E.toInt() else 0xFFFFD54F.toInt(),
            true
        ).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.button_glass_bg)
            setOnClickListener {
                if (!BruceBleManager.isConnected) {
                    startActivity(Intent(this@RfAnalyzerActivity, BruceBleScannerActivity::class.java))
                }
            }
        }
        root.addView(tvConnBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Configuration Card
        val configCard = card()
        val configLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        configLayout.addView(text("📻 Parametry Odbiornika Sub-GHz", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        val spinnerList = listOf(
            "433.92 MHz (Standard BRAMY / PILOTY)",
            "433.42 MHz (Azyjskie / Samochodowe)",
            "434.075 MHz (Precyzyjne Piloty)",
            "434.42 MHz (Nietypowy Skok)",
            "315.00 MHz (Piloty Samochodowe USA)",
            "868.35 MHz (Systemy Alarmowe EU)",
            "Własna częstotliwość (Wpisz poniżej w MHz)"
        )

        spinnerFreq = Spinner(this).apply {
            adapter = ArrayAdapter(this@RfAnalyzerActivity, android.R.layout.simple_spinner_dropdown_item, spinnerList)
            setBackgroundResource(R.drawable.button_glass_bg)
        }
        configLayout.addView(spinnerFreq, verticalParams(dp(4)))

        etCustomFreq = inputField("Dokładna częstotliwość w MHz [np. 433.875]").apply {
            visibility = View.GONE
        }
        configLayout.addView(etCustomFreq, verticalParams(dp(4)))

        spinnerFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 6) {
                    etCustomFreq.visibility = View.VISIBLE
                } else {
                    etCustomFreq.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnStartRx = MaterialButton(this).apply {
            text = "📡 Skanuj & Odbieraj Fale RF (przez Bruce/Lily)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                if (isReceiving) stopRx() else startRx()
            }
        }
        configLayout.addView(btnStartRx, verticalParams(dp(10)))

        val btnSweep433 = MaterialButton(this).apply {
            text = "🔍 Auto-Skanuj Podkanały 433.xx (Sweeper)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isSweeping) stopSweeping() else startFrequencySweep()
            }
        }
        configLayout.addView(btnSweep433, verticalParams(dp(4)))

        tvStatus = text("Status: Gotowy do odbioru fal radiowych", 13f, 0xFF9EADBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        configLayout.addView(tvStatus)

        configCard.addView(configLayout)
        root.addView(configCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Inspector & Editor Card
        val editCard = card()
        val editLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editLayout.addView(text("🛠️ Dekoder Protokołu & Edytor HEX", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(8)) })

        tvDecodedInfo = text("Brak przechwyconego sygnału RF.\nKliknij 'Skanuj & Odbieraj Fale RF' lub wgraj próbkę.", 13f, 0xFF00E5FF.toInt())
        editLayout.addView(tvDecodedInfo)

        etEditHex = inputField("Edytuj ramkę HEX [np. A1 B2 C3 04]")
        editLayout.addView(etEditHex, verticalParams(dp(10)))

        val btnRowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnReTransmit = MaterialButton(this).apply {
            text = "⚡ RE-TRANSMIT (Nadaj RF)"
            setBackgroundColor(0xFF43C59E.toInt())
            setTextColor(Color.BLACK)
            setOnClickListener {
                reTransmitEditedSignal()
            }
        }

        val btnSaveSub = MaterialButton(this).apply {
            text = "💾 Zapisz .sub (Flipper)"
            setBackgroundColor(0xFF283545.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                saveFlipperSubFile()
            }
        }

        btnRowActions.addView(btnReTransmit, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        btnRowActions.addView(btnSaveSub, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        editLayout.addView(btnRowActions, verticalParams(dp(8)))

        editCard.addView(editLayout)
        root.addView(editCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Captures History
        root.addView(text("Przechwycone Sygnały Radiowe (Historia)", 16f, Color.WHITE, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        containerCaptures = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(containerCaptures)
    }

    private var currentSweepIdx = 0
    private val sweepRunnable = object : Runnable {
        override fun run() {
            if (isSweeping) {
                val freq = subChannels433[currentSweepIdx]
                tvStatus.text = "🔍 Skanowanie podkanału: %.3f MHz...".format(Locale.getDefault(), freq / 1000000.0)
                BruceBleRadioBridge.startListeningRf(freq)

                currentSweepIdx = (currentSweepIdx + 1) % subChannels433.size
                handler.postDelayed(this, 1500)
            }
        }
    }

    private fun startFrequencySweep() {
        isSweeping = true
        currentSweepIdx = 0
        handler.post(sweepRunnable)
        Toast.makeText(this, "Rozpoczęto automatyczne skanowanie podkanałów 433.xx", Toast.LENGTH_SHORT).show()
    }

    private fun stopSweeping() {
        isSweeping = false
        handler.removeCallbacks(sweepRunnable)
        tvStatus.text = "Status: Zatrzymano automatyczny skaner częstotliwości."
    }

    private fun startRx() {
        stopSweeping()
        val selectedFreq = getSelectedFrequencyHz()

        isReceiving = true
        btnStartRx.text = "⏹ Zatrzymaj Odbiór RF"
        btnStartRx.setBackgroundColor(0xFFE53935.toInt())
        btnStartRx.setTextColor(Color.WHITE)
        tvStatus.text = "Status: Nasłuch sygnału RF na %.3f MHz...".format(Locale.getDefault(), selectedFreq / 1000000.0)

        BruceBleRadioBridge.startListeningRf(selectedFreq)
    }

    private fun stopRx() {
        isReceiving = false
        btnStartRx.text = "📡 Skanuj & Odbieraj Fale RF (przez Bruce/Lily)"
        btnStartRx.setBackgroundColor(0xFF43C59E.toInt())
        btnStartRx.setTextColor(Color.BLACK)
        tvStatus.text = "Status: Zatrzymano nasłuch RF."

        BruceBleRadioBridge.stopListeningRf()
    }

    private fun updateDecodedUi(result: RfProtocolDecoder.RfDecodeResult) {
        val infoStr = "Protokół: ${result.protocolName}\n" +
                "Częstotliwość: %.3f MHz | Liczba Bitów: %d | Impuls: %d µs\n".format(Locale.getDefault(), result.frequencyHz / 1000000.0, result.bitCount, result.pulseWidthUs) +
                "Ramka HEX Payload: %s\n".format(result.hexPayload) +
                "Adres: %s | Komenda: %s".format(result.addressHex, result.commandHex)

        tvDecodedInfo.text = infoStr
        etEditHex.setText(result.hexPayload)

        if (captureResults.size >= 2) {
            val pred = RfProtocolDecoder.predictNextRollingCode(captureResults[1], captureResults[0])
            Toast.makeText(this, pred, Toast.LENGTH_LONG).show()
        }
    }

    private fun reTransmitEditedSignal() {
        val editedHex = etEditHex.text.toString().trim()
        val freq = getSelectedFrequencyHz()

        if (editedHex.isEmpty()) {
            Toast.makeText(this, "Brak edytowanej ramki HEX!", Toast.LENGTH_SHORT).show()
            return
        }

        BruceBleRadioBridge.transmitRfPayload(freq, editedHex)
    }

    private fun saveFlipperSubFile() {
        val latest = captureResults.firstOrNull()
        if (latest == null) {
            Toast.makeText(this, "Najpierw przechwyć sygnał RF!", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "rf_signal_$timestamp.sub"

        val subContent = "Filetype: Flipper SubGhz Key File\n" +
                "Version: 1\n" +
                "Frequency: ${latest.frequencyHz}\n" +
                "Preset: FuriHalSubGhzPreset2FSKAsync\n" +
                "Protocol: ${latest.protocolName}\n" +
                "Bit: ${latest.bitCount}\n" +
                "Key: ${latest.hexPayload}\n" +
                "TE: ${latest.pulseWidthUs}\n"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Pomiary")
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(subContent.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "Zapisano plik .sub dla Flipper Zero:\nDokumenty/Pomiary/$fileName", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (e: Exception) {
                Log.e("RfAnalyzer", "MediaStore error: ${e.message}")
            }
        }

        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Pomiary")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(subContent, Charsets.UTF_8)
            Toast.makeText(this, "Zapisano plik Flipper .sub:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd zapisu pliku .sub: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderCaptures() {
        containerCaptures.removeAllViews()
        captureResults.forEachIndexed { idx, res ->
            val cCard = card()
            val cLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            cLayout.addView(text("#${captureResults.size - idx} ${res.protocolName}", 14f, 0xFF43C59E.toInt(), true))
            cLayout.addView(text("MHz: %.3f | HEX: %s | Bitów: %d".format(Locale.getDefault(), res.frequencyHz / 1000000.0, res.hexPayload, res.bitCount), 11f, 0xFF9EADBD.toInt()).apply {
                setPadding(0, dp(2), 0, dp(6))
            })

            val btnUse = MaterialButton(this).apply {
                text = "🛠️ Załaduj do Edytora & Transmisji"
                setBackgroundColor(0xFF283545.toInt())
                setTextColor(Color.WHITE)
                setOnClickListener {
                    updateDecodedUi(res)
                }
            }
            cLayout.addView(btnUse, LinearLayout.LayoutParams(-1, -2))

            cCard.addView(cLayout)
            containerCaptures.addView(cCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
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
