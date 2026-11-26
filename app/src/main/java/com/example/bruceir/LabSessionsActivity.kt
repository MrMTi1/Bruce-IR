package com.example.bruceir

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

class LabSessionsActivity : AppCompatActivity() {
    private lateinit var listContainer: LinearLayout
    private var exportSessionId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val sessionId = exportSessionId
        exportSessionId = null
        if (uri == null || sessionId == null) return@registerForActivityResult
        val session = LabSessionStore.getAll(this).firstOrNull { it.id == sessionId } ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(LabSessionStore.exportReport(session))
            } ?: error("Could not open report destination")
        }.onSuccess {
            Toast.makeText(this, R.string.toast_export_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.localizedMessage ?: getString(R.string.lab_export_failed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshSessions()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(7, 9, 13))
            clipToPadding = false
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 28f
            minWidth = dp(48)
            contentDescription = getString(R.string.dialog_close)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleStack.addView(label(getString(R.string.lab_sessions_title), 24f, 0xFFFFFFFF.toInt(), true))
        titleStack.addView(label(getString(R.string.lab_sessions_subtitle), 13f, 0xFF9AA3B2.toInt()))
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)

        val newSessionButton = MaterialButton(this).apply {
            text = getString(R.string.lab_new_session)
            setIconResource(android.R.drawable.ic_input_add)
            setOnClickListener { showSessionEditor(null) }
        }
        val buttonParams = LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(20)
            bottomMargin = dp(16)
        }
        content.addView(newSessionButton, buttonParams)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)
    }

    private fun refreshSessions() {
        listContainer.removeAllViews()
        val sessions = LabSessionStore.getAll(this)
        if (sessions.isEmpty()) {
            val emptyCard = MaterialCardView(this).apply {
                radius = dp(20).toFloat()
                setCardBackgroundColor(0xFF141821.toInt())
                strokeColor = 0xFF293746.toInt()
                strokeWidth = dp(1)
                setContentPadding(dp(18), dp(18), dp(18), dp(18))
            }
            val emptyContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            emptyContent.addView(label(getString(R.string.lab_empty_title), 18f, 0xFFFFFFFF.toInt(), true))
            emptyContent.addView(label(getString(R.string.lab_empty_message), 14f, 0xFFB5BFCC.toInt()).apply {
                setPadding(0, dp(8), 0, 0)
            })
            emptyCard.addView(emptyContent)
            listContainer.addView(emptyCard)
            return
        }

        sessions.forEach { session ->
            val card = MaterialCardView(this).apply {
                radius = dp(20).toFloat()
                setCardBackgroundColor(0xFF141821.toInt())
                strokeColor = if (session.isActive) 0xFF43C59E.toInt() else 0xFF293746.toInt()
                strokeWidth = dp(if (session.isActive) 2 else 1)
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                setOnClickListener { showSessionDetails(session.id) }
                setContentPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(label(session.name, 17f, 0xFFFFFFFF.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
            val state = label(
                getString(if (session.isActive) R.string.lab_active else R.string.lab_completed),
                10f,
                if (session.isActive) 0xFF43C59E.toInt() else 0xFF9AA3B2.toInt(),
                true
            )
            titleRow.addView(state)
            body.addView(titleRow)
            body.addView(label(session.target, 14f, 0xFFDBE3ED.toInt()).apply {
                setPadding(0, dp(5), 0, 0)
            })
            body.addView(label(session.scope, 12f, 0xFF9AA3B2.toInt()).apply {
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
            val info = getString(
                R.string.lab_card_info,
                session.events.size,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.updatedAt))
            )
            body.addView(label(info, 11f, 0xFF8792A2.toInt()).apply {
                setPadding(0, dp(8), 0, 0)
            })
            card.addView(body)
            val params = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
            listContainer.addView(card, params)
        }
    }

    private fun showSessionDetails(id: String) {
        val session = LabSessionStore.getAll(this).firstOrNull { it.id == id } ?: return
        val options = arrayOf(
            getString(R.string.lab_add_note),
            getString(if (session.isActive) R.string.lab_finish else R.string.lab_resume),
            getString(R.string.lab_export),
            getString(R.string.lab_edit),
            getString(R.string.lab_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(session.name)
            .setMessage(buildString {
                appendLine("${getString(R.string.lab_target)}: ${session.target}")
                appendLine()
                appendLine("${getString(R.string.lab_scope)}:")
                appendLine(session.scope)
                appendLine()
                appendLine("${getString(R.string.lab_notes)}:")
                appendLine(session.notes.ifBlank { getString(R.string.lab_no_notes) })
                appendLine()
                appendLine("${getString(R.string.lab_activity)} (${session.events.size})")
                if (session.events.isEmpty()) {
                    appendLine(getString(R.string.lab_no_activity))
                } else {
                    session.events.takeLast(12).forEach {
                        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timestamp))
                        appendLine("$time · ${it.type}: ${it.details}")
                    }
                }
            })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddNote(session.id)
                    1 -> {
                        LabSessionStore.setActive(this, session.id, !session.isActive)
                        refreshSessions()
                    }
                    2 -> exportSession(session)
                    3 -> showSessionEditor(session)
                    4 -> confirmDelete(session)
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showSessionEditor(existing: LabSession?) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = editField(getString(R.string.lab_session_name), existing?.name.orEmpty())
        val target = editField(getString(R.string.lab_target_hint), existing?.target.orEmpty())
        val scope = editField(getString(R.string.lab_scope_hint), existing?.scope.orEmpty())
        val notes = if (existing == null) editField(getString(R.string.lab_notes_hint), "", multiline = true) else null
        fields.addView(name)
        fields.addView(target)
        fields.addView(scope)
        notes?.let(fields::addView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (existing == null) R.string.lab_new_session else R.string.lab_edit))
            .setView(fields)
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sessionName = name.text.toString().trim()
                val sessionTarget = target.text.toString().trim()
                val sessionScope = scope.text.toString().trim()
                if (sessionName.isEmpty() || sessionTarget.isEmpty() || sessionScope.isEmpty()) {
                    Toast.makeText(this, R.string.lab_required, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (existing == null) {
                    LabSessionStore.create(
                        this,
                        sessionName,
                        sessionTarget,
                        sessionScope,
                        notes?.text?.toString().orEmpty()
                    )
                } else {
                    LabSessionStore.updateDetails(this, existing.id, sessionName, sessionTarget, sessionScope)
                }
                dialog.dismiss()
                refreshSessions()
                Toast.makeText(this, R.string.lab_saved, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showAddNote(sessionId: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.lab_note_hint)
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.lab_add_note)
            .setView(input)
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val note = input.text.toString().trim()
                if (note.isBlank()) {
                    input.error = getString(R.string.lab_note_hint)
                    return@setOnClickListener
                }
                LabSessionStore.addNote(this, sessionId, note)
                dialog.dismiss()
                refreshSessions()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(session: LabSession) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lab_delete))
            .setMessage(getString(R.string.lab_delete_confirm))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.lab_delete) { _, _ ->
                LabSessionStore.delete(this, session.id)
                refreshSessions()
            }
            .show()
    }

    private fun exportSession(session: LabSession) {
        exportSessionId = session.id
        val safeName = session.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50).ifBlank { "session" }
        exportLauncher.launch("BruceIR_$safeName.txt")
    }

    private fun editField(hintText: String, value: String, multiline: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        maxLines = if (multiline) 3 else 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
