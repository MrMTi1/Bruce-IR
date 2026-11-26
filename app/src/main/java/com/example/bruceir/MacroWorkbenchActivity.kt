package com.example.bruceir

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MacroWorkbenchActivity : AppCompatActivity() {
    private lateinit var manager: MacroManager
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var irRoot: IrFolder? = null
    private var irCommandIndex: List<IrCommandOrigin.Match> = emptyList()
    private var isIrCommandIndexReady = false
    private var selectedName: String? = null
    private var activeMacroName: String? = null
    private var runningSourcePath: List<String>? = null
    private var running = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppProfileManager.guardModule(this, AppProfileManager.ModuleGroup.MACROS)) return
        window.statusBarColor = 0xFF070A0F.toInt()
        window.navigationBarColor = 0xFF070A0F.toInt()
        manager = MacroManager(this, IrTransmitter(this))
        buildScreen()
        render()
        lifecycleScope.launch {
            irRoot = withContext(Dispatchers.IO) { BruceUtils.loadAllDataSuspend(this@MacroWorkbenchActivity) }
            irCommandIndex = withContext(Dispatchers.Default) {
                irRoot?.let(IrCommandOrigin::searchCommands).orEmpty()
            }
            isIrCommandIndexReady = true
            render()
        }
    }

    override fun onStop() {
        if (running) {
            manager.stop()
            running = false
            activeMacroName = null
            status.setText(R.string.macro_workbench_stopped)
            render()
        }
        super.onStop()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070A0F.toInt())
            setPadding(dp(18), dp(14), dp(18), 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = button("‹", outlined = true).apply {
            textSize = 24f
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleColumn.addView(label(getString(R.string.workbench_eyebrow), 10f, 0xFF43C59E.toInt(), true))
        titleColumn.addView(label(getString(R.string.macro_workbench_title), 22f, Color.WHITE, true))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(getString(R.string.macro_workbench_new)) { createMacro() })
        root.addView(header)
        root.addView(label(getString(R.string.macro_workbench_intro), 13f, 0xFF9EADBD.toInt()).apply {
            setPadding(0, dp(10), 0, dp(8))
        })
        root.addView(button(getString(R.string.macro_workbench_search)) {
            showIrCodeSearch()
        }, verticalParams(bottom = 10))
        status = label("", 12f, 0xFF9EADBD.toInt()).apply {
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        status.setOnClickListener {
            runningSourcePath?.let(::openRemotePath)
        }
        root.addView(status)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun render() {
        content.removeAllViews()
        val macros = manager.getAllMacros()
        if (selectedName !in macros.map { it.name }) selectedName = null
        if (macros.isEmpty()) {
            val emptyCard = card()
            val emptyContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            emptyContent.addView(label(getString(R.string.macro_workbench_empty), 18f, Color.WHITE, true))
            emptyContent.addView(label(getString(R.string.macro_workbench_empty_hint), 13f, 0xFF9EADBD.toInt()).apply {
                setPadding(0, dp(6), 0, 0)
            })
            emptyCard.addView(emptyContent)
            content.addView(emptyCard, verticalParams(bottom = 10))
            return
        }

        macros.forEach { macro ->
            val chosen = macro.name == selectedName
            val isActiveMacro = running && activeMacroName == macro.name
            val macroCard = card().apply {
                strokeColor = if (chosen) 0xFF43C59E.toInt() else 0xFF283545.toInt()
                isClickable = true
                isFocusable = true
                setOnClickListener { runOrStop(macro) }
                setOnLongClickListener {
                    showMacroActions(macro)
                    true
                }
            }
            val macroRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val macroContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            macroContent.addView(label(macro.name, 16f, Color.WHITE, true).apply {
                setOnLongClickListener {
                    showMacroActions(macro)
                    true
                }
            })
            macroContent.addView(label(
                getString(R.string.macro_workbench_command_count, macro.commands.size, macro.delayMs.toInt()),
                12f,
                0xFF9EADBD.toInt()
            ).apply {
                setPadding(0, dp(4), 0, 0)
                setOnLongClickListener {
                    showMacroActions(macro)
                    true
                }
            })
            macroRow.addView(macroContent, LinearLayout.LayoutParams(0, -2, 1f))
            
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            
            val btnEdit = button("⚙️", outlined = true).apply {
                setOnClickListener {
                    selectedName = macro.name
                    render()
                }
            }
            actionRow.addView(btnEdit, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })

            val btnRun = button(
                getString(if (isActiveMacro) R.string.macro_workbench_stop else R.string.macro_workbench_run)
            ) {
                if (isActiveMacro) {
                    stopMacro()
                } else if (running) {
                    Toast.makeText(this@MacroWorkbenchActivity, R.string.macro_workbench_already_running, Toast.LENGTH_SHORT).show()
                } else {
                    runMacro(macro)
                }
            }
            actionRow.addView(btnRun)
            macroRow.addView(actionRow)
            macroCard.addView(macroRow)
            content.addView(macroCard, verticalParams(bottom = 8))
        }

        val selected = macros.firstOrNull { it.name == selectedName } ?: return
        val details = card()
        val detailContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(detailContent)
        detailContent.addView(label(selected.name, 18f, Color.WHITE, true))
        detailContent.addView(button(getString(R.string.macro_workbench_done_editing), outlined = true) {
            selectedName = null
            render()
        }, verticalParams(top = 8))
        detailContent.addView(label(getString(R.string.macro_workbench_commands), 10f, 0xFF62CDB1.toInt(), true).apply {
            setPadding(0, dp(14), 0, dp(6))
        })
        if (selected.commands.isEmpty()) {
            detailContent.addView(label(getString(R.string.macro_workbench_no_commands), 13f, 0xFF9EADBD.toInt()))
        } else {
            selected.commands.forEachIndexed { index, command ->
                val commandRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                }
                val commandDetails = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                commandDetails.addView(label("${index + 1}. ${command.name}", 13f, Color.WHITE).apply {
                    maxLines = 2
                })
                val sourcePath = command.sourceRemotePath
                    ?: irRoot?.let { IrCommandOrigin.findCommandPath(it, command) }
                if (!sourcePath.isNullOrEmpty()) {
                    commandDetails.addView(label(
                        getString(R.string.macro_command_source, sourcePath.joinToString(" › ")),
                        10f,
                        0xFF62CDB1.toInt()
                    ).apply {
                        maxLines = 2
                        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                        contentDescription = getString(
                            R.string.macro_command_open_remote,
                            sourcePath.joinToString(" › ")
                        )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { openRemotePath(sourcePath) }
                    })
                    commandRow.isClickable = true
                    commandRow.isFocusable = true
                    commandRow.setOnClickListener { openRemotePath(sourcePath) }
                }
                commandRow.addView(commandDetails, LinearLayout.LayoutParams(0, -2, 1f))
                commandRow.addView(smallAction("↑") {
                    if (index > 0) {
                        java.util.Collections.swap(selected.commands, index, index - 1)
                        manager.updateMacro(selected)
                        render()
                    }
                })
                commandRow.addView(smallAction("↓") {
                    if (index < selected.commands.lastIndex) {
                        java.util.Collections.swap(selected.commands, index, index + 1)
                        manager.updateMacro(selected)
                        render()
                    }
                })
                commandRow.addView(smallAction("×") {
                    selected.commands.removeAt(index)
                    manager.updateMacro(selected)
                    render()
                })
                detailContent.addView(commandRow)
                if (index < selected.commands.lastIndex) detailContent.addView(divider())
            }
        }

        content.addView(details, verticalParams(top = 6, bottom = 18))
    }

    private fun showIrCodeSearch() {
        if (!isIrCommandIndexReady) {
            Toast.makeText(this, R.string.macro_workbench_search_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val density = resources.displayMetrics.density
        val selectedKeys = linkedSetOf<String>()
        var currentMatches = emptyList<IrCommandOrigin.Match>()
        val input = EditText(this).apply {
            hint = getString(R.string.macro_workbench_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val summary = label(getString(R.string.macro_workbench_search_hint), 12f, 0xFF9EADBD.toInt())
        val bulkSelectionButton = button(getString(R.string.macro_workbench_select_all), outlined = true)
        val resultsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(resultsView)
            layoutParams = LinearLayout.LayoutParams(-1, (320 * density).toInt())
        }
        val searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(summary, verticalParams(top = 6, bottom = 6))
            addView(bulkSelectionButton, verticalParams(bottom = 6))
            addView(scroll)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.macro_workbench_search)
            .setView(searchPanel)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.macro_workbench_build_macro, null)
            .create()

        fun resultKey(match: IrCommandOrigin.Match): String =
            "${match.remotePath.joinToString("/")}|${match.command.name}|${match.command.frequency}|${match.command.pattern.joinToString(",")}"

        fun updateBulkSelectionButton() {
            val visibleMatches = currentMatches.take(MAX_SEARCH_RESULTS)
            val allVisibleSelected = visibleMatches.isNotEmpty() &&
                visibleMatches.all { resultKey(it) in selectedKeys }
            bulkSelectionButton.setText(
                if (allVisibleSelected) R.string.macro_workbench_deselect_all
                else R.string.macro_workbench_select_all
            )
            bulkSelectionButton.isEnabled = visibleMatches.isNotEmpty()
        }

        fun updateResults(query: String) {
            val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            currentMatches = if (terms.isEmpty()) {
                emptyList()
            } else {
                irCommandIndex.filter { match ->
                    val haystack = "${match.command.name} ${match.remotePath.joinToString(" ")}"
                    terms.all { haystack.contains(it, ignoreCase = true) }
                }
            }
            resultsView.removeAllViews()
            summary.text = if (terms.isEmpty()) {
                getString(R.string.macro_workbench_search_hint)
            } else {
                getString(
                    R.string.macro_workbench_search_count,
                    currentMatches.size,
                    minOf(currentMatches.size, MAX_SEARCH_RESULTS)
                )
            }
            currentMatches.take(MAX_SEARCH_RESULTS).forEach { match ->
                val checkbox = CheckBox(this).apply {
                    text = "${match.command.name}\n${match.remotePath.joinToString(" › ")}"
                    isChecked = resultKey(match) in selectedKeys
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedKeys.add(resultKey(match))
                        else selectedKeys.remove(resultKey(match))
                        updateBulkSelectionButton()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4) }
                }
                resultsView.addView(checkbox)
            }
            updateBulkSelectionButton()
        }

        bulkSelectionButton.setOnClickListener {
            val visibleMatches = currentMatches.take(MAX_SEARCH_RESULTS)
            val allVisibleSelected = visibleMatches.isNotEmpty() &&
                visibleMatches.all { resultKey(it) in selectedKeys }
            visibleMatches.forEach { match ->
                val key = resultKey(match)
                if (allVisibleSelected) selectedKeys.remove(key)
                else selectedKeys.add(key)
            }
            updateResults(input.text.toString())
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateResults(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = currentMatches.filter { resultKey(it) in selectedKeys }
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.macro_workbench_search_select, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showMacroNameForSearch(selected, input.text.toString(), dialog)
            }
        }
        dialog.show()
    }

    private fun showMacroNameForSearch(
        matches: List<IrCommandOrigin.Match>,
        query: String,
        searchDialog: AlertDialog
    ) {
        val suggestedName = query.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), "_")
            .uppercase()
            .take(40)
            .ifBlank { "IR_MACRO" }
        val nameInput = EditText(this).apply {
            setText(suggestedName)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_workbench_name_hint)
            .setView(nameInput)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.macro_workbench_build_macro) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.macro_workbench_name_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val macro = MacroSet(name).apply {
                    commands.addAll(matches.map { match ->
                        match.command.copy().apply { sourceRemotePath = match.remotePath }
                    })
                }
                manager.updateMacro(macro)
                selectedName = null
                searchDialog.dismiss()
                render()
                Toast.makeText(
                    this,
                    getString(R.string.macro_workbench_created_from_search, macro.commands.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun createMacro() {
        val input = EditText(this).apply {
            hint = getString(R.string.macro_workbench_name_hint)
            setSingleLine(true)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_workbench_create_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim().uppercase()
                if (name.isNotEmpty()) {
                    val macro = MacroSet(name)
                    manager.updateMacro(macro)
                    selectedName = null
                    render()
                }
            }
            .show()
    }

    private fun showSettings(macro: MacroSet) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val delayInput = EditText(this).apply {
            hint = getString(R.string.macro_workbench_delay_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(macro.delayMs.toString())
            setTextColor(Color.WHITE)
        }
        val looping = MaterialSwitch(this).apply {
            text = getString(R.string.macro_workbench_loop)
            setTextColor(0xFFB7C5D4.toInt())
            isChecked = macro.isLooping
        }
        panel.addView(delayInput)
        panel.addView(looping)
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_workbench_settings)
            .setView(panel)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                macro.delayMs = delayInput.text.toString().toLongOrNull()?.coerceIn(0L, 60_000L) ?: macro.delayMs
                macro.isLooping = looping.isChecked
                manager.updateMacro(macro)
                render()
            }
            .show()
    }

    private fun confirmDelete(macro: MacroSet) {
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_workbench_delete)
            .setMessage(R.string.macro_workbench_delete_confirm)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                manager.saveAllMacros(manager.getAllMacros().filterNot { it.name == macro.name })
                if (selectedName == macro.name) selectedName = null
                render()
            }
            .show()
    }

    private fun showMacroActions(macro: MacroSet) {
        val actions = arrayOf(
            getString(R.string.macro_workbench_run),
            getString(R.string.macro_workbench_edit_commands),
            getString(R.string.macro_workbench_settings),
            getString(R.string.macro_workbench_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(macro.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runOrStop(macro)
                    1 -> {
                        selectedName = macro.name
                        render()
                        showMacroCommandEditor(macro)
                    }
                    2 -> showSettings(macro)
                    3 -> confirmDelete(macro)
                }
            }
            .show()
    }

    private fun runOrStop(macro: MacroSet) {
        when {
            running && activeMacroName == macro.name -> stopMacro()
            running -> Toast.makeText(this, R.string.macro_workbench_already_running, Toast.LENGTH_SHORT).show()
            else -> runMacro(macro)
        }
    }

    private fun showMacroCommandEditor(macro: MacroSet) {
        if (macro.commands.isEmpty()) {
            Toast.makeText(this, R.string.macro_workbench_no_commands, Toast.LENGTH_SHORT).show()
            return
        }
        val commandLabels = macro.commands.mapIndexed { index, command ->
            val sourcePath = command.sourceRemotePath
                ?: irRoot?.let { IrCommandOrigin.findCommandPath(it, command) }
            buildString {
                append("${index + 1}. ${command.name}")
                if (!sourcePath.isNullOrEmpty()) {
                    append("\n")
                    append(getString(R.string.macro_command_source, sourcePath.joinToString(" › ")))
                }
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.macro_workbench_edit_commands_title, macro.name))
            .setItems(commandLabels) { _, index ->
                val command = macro.commands[index]
                val actions = arrayOf(
                    getString(R.string.macro_workbench_open_remote),
                    getString(R.string.macro_workbench_move_up),
                    getString(R.string.macro_workbench_move_down),
                    getString(R.string.macro_workbench_remove_command)
                )
                AlertDialog.Builder(this)
                    .setTitle(command.name)
                    .setItems(actions) { _, action ->
                        when (action) {
                            0 -> {
                                val path = command.sourceRemotePath
                                    ?: irRoot?.let { IrCommandOrigin.findCommandPath(it, command) }
                                if (path.isNullOrEmpty()) {
                                    Toast.makeText(this, R.string.macro_workbench_remote_missing, Toast.LENGTH_SHORT).show()
                                } else {
                                    openRemotePath(path)
                                }
                            }
                            1 -> if (index > 0) {
                                java.util.Collections.swap(macro.commands, index, index - 1)
                                manager.updateMacro(macro)
                                showMacroCommandEditor(macro)
                            }
                            2 -> if (index < macro.commands.lastIndex) {
                                java.util.Collections.swap(macro.commands, index, index + 1)
                                manager.updateMacro(macro)
                                showMacroCommandEditor(macro)
                            }
                            3 -> {
                                macro.commands.removeAt(index)
                                manager.updateMacro(macro)
                                render()
                                showMacroCommandEditor(macro)
                            }
                        }
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun runMacro(macro: MacroSet) {
        if (macro.commands.isEmpty()) return
        running = true
        activeMacroName = macro.name
        runningSourcePath = null
        status.text = getString(
            R.string.macro_workbench_running,
            1,
            macro.commands.size,
            macro.commands.first().name
        )
        render()
        manager.start(
            macro,
            { current, total, _ ->
                val command = macro.commands.getOrNull(current - 1)
                val sourcePath = command?.sourceRemotePath
                    ?: command?.let { target -> irRoot?.let { IrCommandOrigin.findCommandPath(it, target) } }
                val activeSourcePath = sourcePath?.takeIf { it.isNotEmpty() }
                runningSourcePath = activeSourcePath
                status.text = if (activeSourcePath == null) {
                    getString(
                        R.string.macro_workbench_running,
                        current,
                        total,
                        command?.name.orEmpty()
                    )
                } else {
                    getString(
                        R.string.macro_workbench_running_source,
                        current,
                        total,
                        command?.name.orEmpty(),
                        activeSourcePath.joinToString(" › ")
                    )
                }
            },
            {},
            {
                running = false
                activeMacroName = null
                runningSourcePath = null
                status.setText(R.string.macro_workbench_finished)
                render()
            }
        )
    }

    private fun openRemotePath(path: List<String>) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("ACTION", "OPEN_IR_REMOTE_PATH")
            putStringArrayListExtra("IR_REMOTE_PATH", ArrayList(path))
        })
    }

    private fun stopMacro() {
        manager.stop()
        running = false
        activeMacroName = null
        status.setText(R.string.macro_workbench_stopped)
        render()
    }

    private fun smallAction(caption: String, action: () -> Unit) = button(caption, outlined = true, action = action).apply {
        minWidth = dp(40)
        setPadding(0, 0, 0, 0)
    }

    private fun button(
        caption: String,
        outlined: Boolean = false,
        action: () -> Unit = {}
    ) = MaterialButton(
        this,
        null,
        if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
    ).apply {
        text = caption
        setOnClickListener { action() }
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(0xFF141B25.toInt())
        strokeColor = 0xFF283545.toInt()
        strokeWidth = dp(1)
        cardElevation = 0f
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFF283545.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(3)
            bottomMargin = dp(3)
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun verticalParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
        bottomMargin = bottom
    }

    companion object {
        private const val MAX_SEARCH_RESULTS = 100
    }
}
