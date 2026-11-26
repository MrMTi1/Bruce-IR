package com.example.bruceir

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import java.io.ByteArrayOutputStream
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PcapConverterActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var saveButton: MaterialButton
    private var outputContent: String? = null
    private var outputName = "BruceIR_Capture.hc22000"

    private val inputPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) convert(uri)
    }
    private val outputPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveOutput(uri)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070A0F.toInt()
        window.navigationBarColor = 0xFF070A0F.toInt()
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070A0F.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = button("‹", outlined = true) { finish() }.apply { textSize = 24f }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(getString(R.string.pcap_workbench_title), 22f, Color.WHITE, true).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(header)
        root.addView(label(getString(R.string.pcap_workbench_intro), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(10), 0, dp(16))
        })

        val details = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            setCardBackgroundColor(0xFF141B25.toInt())
            strokeColor = 0xFF283545.toInt()
            strokeWidth = dp(1)
            cardElevation = 0f
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
        }
        status = label(getString(R.string.pcap_workbench_ready), 14f, 0xFFB7C5D4.toInt())
        details.addView(status)
        root.addView(details, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        root.addView(button(getString(R.string.pcap_workbench_choose)) {
            inputPicker.launch("*/*")
        }, LinearLayout.LayoutParams(-1, dp(50)))
        saveButton = button(getString(R.string.pcap_workbench_save), outlined = true) {
            outputPicker.launch(outputName)
        }.apply {
            isEnabled = false
        }
        root.addView(saveButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun convert(uri: Uri) {
        saveButton.isEnabled = false
        outputContent = null
        status.setText(R.string.pcap_workbench_reading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = readBounded(uri)
                    val converted = HashcatConverter.convertPcapToHc22000(bytes)
                    val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "capture")
                        .substringBeforeLast('.')
                        .replace(Regex("[^A-Za-z0-9_-]"), "_")
                        .ifBlank { "capture" }
                    converted to name
                }
            }
            result.onSuccess { (converted, name) ->
                if (converted.isBlank()) {
                    status.setText(R.string.pcap_workbench_no_candidates)
                } else {
                    outputContent = converted
                    outputName = "$name.hc22000"
                    saveButton.isEnabled = true
                    status.text = getString(
                        R.string.pcap_workbench_converted,
                        converted.lineSequence().count(),
                        name
                    )
                }
            }.onFailure {
                status.setText(R.string.pcap_workbench_failed)
            }
        }
    }

    private fun readBounded(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        val input = contentResolver.openInputStream(uri) ?: error("Unable to open capture")
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 128 * 1024 * 1024) { "Capture exceeds 128 MB limit" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun saveOutput(uri: Uri) {
        val content = outputContent ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                ?: error("Unable to open output file")
        }.onSuccess {
            Toast.makeText(this, R.string.pcap_workbench_saved, Toast.LENGTH_SHORT).show()
        }.onFailure {
            status.setText(R.string.pcap_workbench_failed)
        }
    }

    private fun button(caption: String, outlined: Boolean = false, action: () -> Unit) =
        MaterialButton(
            this,
            null,
            if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = caption
            setOnClickListener { action() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
}
