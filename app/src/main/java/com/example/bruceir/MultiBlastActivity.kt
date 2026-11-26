package com.example.bruceir

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MultiBlastActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.IR)) return
        window.statusBarColor = Color.rgb(7, 10, 15)
        window.navigationBarColor = Color.rgb(7, 10, 15)
        buildScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
        titleStack.addView(text("TRANSMITTER ARRAY", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Multi-Blast Broadcaster", 28f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Simultaneously broadcast IR commands across Internal IR, USB OTG, Wi-Fi, and BLE transmitters.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        val card = card()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(text("Select Command to Broadcast", 16f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(8))
        })

        val allData = BruceUtils.loadAllData(this)
        val commands = mutableListOf<Command>()
        findAllCommands(allData, commands)

        if (commands.isEmpty()) {
            layout.addView(text("No IR commands available.", 12f, 0xFFE53935.toInt()))
        } else {
            commands.take(12).forEach { cmd ->
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "📡 Blast: ${cmd.name}"
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        val transmitter = IrTransmitter(this@MultiBlastActivity)
                        val modes = listOf(IrTransmitter.Mode.INTERNAL, IrTransmitter.Mode.USB, IrTransmitter.Mode.WIFI, IrTransmitter.Mode.BRUCE_BLE)
                        modes.forEach { mode ->
                            transmitter.currentMode = mode
                            transmitter.transmit(cmd.frequency, cmd.pattern)
                        }
                        transmitter.currentMode = IrTransmitter.Mode.INTERNAL
                        Toast.makeText(this@MultiBlastActivity, "Blasted '${cmd.name}' across all transmitters!", Toast.LENGTH_SHORT).show()
                    }
                }
                layout.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }

        card.addView(layout)
        root.addView(card)
    }

    private fun findAllCommands(folder: IrFolder, list: MutableList<Command>) {
        folder.items.forEach {
            if (it is Command && it.frequency > 0) list.add(it)
            else if (it is IrFolder) findAllCommands(it, list)
        }
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
