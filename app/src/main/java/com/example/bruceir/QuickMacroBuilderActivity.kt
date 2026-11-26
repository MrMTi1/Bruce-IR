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

class QuickMacroBuilderActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.MACROS)) return
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
        titleStack.addView(text("MACRO STUDIO", 10f, 0xFF43C59E.toInt(), true))
        titleStack.addView(text("Quick Macro Builder", 28f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(header)

        root.addView(text("Instantly chain favorite remote commands into a custom automated macro sequence.", 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(6), 0, dp(14))
        })

        val card = card()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(text("Select Commands to Chain", 16f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(8))
        })

        val allData = BruceUtils.loadAllData(this)
        val commands = mutableListOf<Command>()
        findAllCommands(allData, commands)

        if (commands.isEmpty()) {
            layout.addView(text("No IR commands found in library. Import or add remotes first.", 12f, 0xFFE53935.toInt()))
        } else {
            val selectedCommands = mutableListOf<Command>()
            commands.take(15).forEach { cmd ->
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "+ ${cmd.name} (${cmd.frequency}Hz)"
                    isCheckable = true
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        if (isChecked) {
                            selectedCommands.add(cmd)
                            setBackgroundColor(0x3343C59E)
                        } else {
                            selectedCommands.remove(cmd)
                            setBackgroundColor(Color.TRANSPARENT)
                        }
                    }
                }
                layout.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }

            val saveBtn = MaterialButton(this).apply {
                text = "Save as New Macro"
                setBackgroundColor(0xFF43C59E.toInt())
                setTextColor(Color.BLACK)
                setOnClickListener {
                    if (selectedCommands.isEmpty()) {
                        Toast.makeText(this@QuickMacroBuilderActivity, "Please select at least one command", Toast.LENGTH_SHORT).show()
                    } else {
                        val macroName = "Quick Macro " + System.currentTimeMillis() % 1000
                        val macro = MacroSet(macroName, selectedCommands.toMutableList(), 600, false)
                        val manager = MacroManager(this@QuickMacroBuilderActivity, IrTransmitter(this@QuickMacroBuilderActivity))
                        manager.updateMacro(macro)
                        Toast.makeText(this@QuickMacroBuilderActivity, "Macro '$macroName' created successfully!", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            layout.addView(saveBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
