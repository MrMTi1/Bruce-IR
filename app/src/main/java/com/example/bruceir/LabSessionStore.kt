package com.example.bruceir

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class LabSessionEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val details: String
)

data class LabSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var target: String,
    var scope: String,
    var notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    var isActive: Boolean = true,
    var finishedAt: Long? = null,
    val events: MutableList<LabSessionEvent> = mutableListOf()
)

object LabSessionStore {
    private const val PREFS = "lab_sessions"
    private const val KEY_SESSIONS = "sessions"
    private const val MAX_EVENTS_PER_SESSION = 500
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<LabSession>>() {}.type

    @Synchronized
    fun getAll(context: Context): List<LabSession> = load(context)
        .sortedByDescending { it.updatedAt }

    @Synchronized
    fun create(
        context: Context,
        name: String,
        target: String,
        scope: String,
        notes: String
    ): LabSession {
        val sessions = load(context)
        sessions.firstOrNull { it.isActive }?.let {
            it.isActive = false
            it.finishedAt = System.currentTimeMillis()
        }

        val now = System.currentTimeMillis()
        val session = LabSession(
            name = name.trim(),
            target = target.trim(),
            scope = scope.trim(),
            notes = notes.trim(),
            createdAt = now,
            updatedAt = now
        )
        if (notes.isNotBlank()) {
            session.events.add(LabSessionEvent(type = "NOTE", details = notes.trim()))
        }
        sessions.add(session)
        persist(context, sessions)
        return session
    }

    @Synchronized
    fun updateDetails(
        context: Context,
        id: String,
        name: String,
        target: String,
        scope: String
    ) {
        val sessions = load(context)
        sessions.firstOrNull { it.id == id }?.let {
            it.name = name.trim()
            it.target = target.trim()
            it.scope = scope.trim()
            it.updatedAt = System.currentTimeMillis()
            persist(context, sessions)
        }
    }

    @Synchronized
    fun addNote(context: Context, id: String, note: String) {
        addEventTo(context, id, "NOTE", note)
    }

    @Synchronized
    fun addEventTo(context: Context, id: String, type: String, details: String) {
        val safeDetails = details.trim().take(2000)
        if (safeDetails.isEmpty()) return
        val sessions = load(context)
        sessions.firstOrNull { it.id == id }?.let { session ->
            session.events.add(LabSessionEvent(type = type.take(40), details = safeDetails))
            while (session.events.size > MAX_EVENTS_PER_SESSION) {
                session.events.removeAt(0)
            }
            session.updatedAt = System.currentTimeMillis()
            persist(context, sessions)
        }
    }

    @Synchronized
    fun recordEvent(context: Context, type: String, details: String) {
        val active = load(context).lastOrNull { it.isActive } ?: return
        addEventTo(context, active.id, type, details)
    }

    @Synchronized
    fun setActive(context: Context, id: String, active: Boolean) {
        val sessions = load(context)
        val now = System.currentTimeMillis()
        if (active) {
            sessions.filter { it.isActive && it.id != id }.forEach {
                it.isActive = false
                it.finishedAt = now
            }
        }
        sessions.firstOrNull { it.id == id }?.let {
            it.isActive = active
            it.finishedAt = if (active) null else now
            it.updatedAt = now
            persist(context, sessions)
        }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        persist(context, load(context).filterNot { it.id == id })
    }

    fun exportReport(session: LabSession): String {
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return buildString {
            appendLine("BRUCE IR LAB — SESSION REPORT")
            appendLine("Name: ${session.name}")
            appendLine("Target: ${session.target}")
            appendLine("Authorized scope: ${session.scope}")
            appendLine("Status: ${if (session.isActive) "ACTIVE" else "COMPLETED"}")
            appendLine("Started: ${dateFormat.format(Date(session.createdAt))}")
            session.finishedAt?.let { appendLine("Finished: ${dateFormat.format(Date(it))}") }
            appendLine()
            appendLine("Session notes")
            appendLine(session.notes.ifBlank { "(none)" })
            appendLine()
            appendLine("Activity")
            if (session.events.isEmpty()) {
                appendLine("(no recorded events)")
            } else {
                session.events.forEach { event ->
                    appendLine("[${dateFormat.format(Date(event.timestamp))}] ${event.type}: ${event.details}")
                }
            }
        }
    }

    private fun load(context: Context): MutableList<LabSession> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS, null) ?: return mutableListOf()
        return runCatching {
            gson.fromJson<MutableList<LabSession>>(json, listType) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    private fun persist(context: Context, sessions: List<LabSession>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS, gson.toJson(sessions))
            .apply()
    }
}
