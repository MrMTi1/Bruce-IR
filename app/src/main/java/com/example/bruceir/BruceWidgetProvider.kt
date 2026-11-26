package com.example.bruceir

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import com.google.gson.Gson

class BruceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CLICK) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                triggerAction(context, appWidgetId)
            }
        } else if (intent.action == ACTION_OPEN_APP) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                openAppTarget(context, appWidgetId)
            }
        }
    }

    private fun triggerAction(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val label = prefs.getString("widget_${appWidgetId}_label", "BRUCE") ?: "BRUCE"
        val type = prefs.getString("widget_${appWidgetId}_type", null)
        val data = prefs.getString("widget_${appWidgetId}_data", null)

        // Widget nie może omijać profilu aplikacji (np. 1111 nie ma modułów IR/makr).
        val requiredGroup = if (type == "macro") {
            AppProfileManager.ModuleGroup.MACROS
        } else {
            AppProfileManager.ModuleGroup.IR
        }
        if (!AppProfileManager.isModuleAvailable(context, requiredGroup)) {
            return
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)

        fun showLoading(text: String) {
            val views = RemoteViews(context.packageName, R.layout.widget_command)
            views.setTextViewText(R.id.widgetText, text)
            views.setViewVisibility(R.id.widgetProgress, View.VISIBLE)
            views.setViewVisibility(R.id.widgetIcon, View.INVISIBLE)

            val clickIntent = Intent(context, BruceWidgetProvider::class.java).apply {
                action = ACTION_CLICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val clickPending = PendingIntent.getBroadcast(context, appWidgetId, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val openIntent = Intent(context, BruceWidgetProvider::class.java).apply {
                action = ACTION_OPEN_APP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val openPending = PendingIntent.getBroadcast(context, appWidgetId + 10000, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            views.setOnClickPendingIntent(R.id.widgetIcon, clickPending)
            views.setOnClickPendingIntent(R.id.widgetText, openPending)
            views.setOnClickPendingIntent(R.id.widgetRoot, clickPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        showLoading("$label...")

        if (type == "command" && data != null) {
            val cmd = Gson().fromJson(data, Command::class.java)
            val transmitter = IrTransmitter(context)
            try {
                transmitter.transmit(cmd.frequency, cmd.pattern)
                BruceUtils.showNotification(context, "BruceIR Widget Sent", "Command: ${cmd.name}")
            } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).postDelayed({
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }, 800)
        } else if (type == "macro" && data != null) {
            val macro = Gson().fromJson(data, MacroSet::class.java).copy(isLooping = false)
            val transmitter = IrTransmitter(context)
            val macroManager = MacroManager(context, transmitter)
            macroManager.start(
                macro,
                onProgress = { current, total, _ ->
                    showLoading("$label ($current/$total)")
                },
                onCommandSent = {},
                onFinished = {
                    BruceUtils.showNotification(context, "BruceIR Macro Finished", "Macro: $label")
                    Handler(Looper.getMainLooper()).postDelayed({
                        updateAppWidget(context, appWidgetManager, appWidgetId)
                    }, 500)
                }
            )
        }
    }

    private fun openAppTarget(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val type = prefs.getString("widget_${appWidgetId}_type", null)
        val data = prefs.getString("widget_${appWidgetId}_data", null)

        val intent = if (type == "macro") {
            Intent(context, MacroWorkbenchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (data != null) {
                    try {
                        val cmd = Gson().fromJson(data, Command::class.java)
                        if (cmd.sourceRemotePath != null) {
                            putStringArrayListExtra("IR_REMOTE_PATH", ArrayList(cmd.sourceRemotePath!!))
                            action = "com.example.bruceir.ACTION_SEND_IR"
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        context.startActivity(intent)
    }

    companion object {
        const val ACTION_CLICK = "com.example.bruceir.ACTION_WIDGET_CLICK"
        const val ACTION_OPEN_APP = "com.example.bruceir.ACTION_WIDGET_OPEN_APP"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val label = prefs.getString("widget_${appWidgetId}_label", "BRUCE")
            
            val clickIntent = Intent(context, BruceWidgetProvider::class.java).apply {
                action = ACTION_CLICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val clickPending = PendingIntent.getBroadcast(context, appWidgetId, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val openIntent = Intent(context, BruceWidgetProvider::class.java).apply {
                action = ACTION_OPEN_APP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val openPending = PendingIntent.getBroadcast(context, appWidgetId + 10000, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val views = RemoteViews(context.packageName, R.layout.widget_command)
            views.setTextViewText(R.id.widgetText, label)
            views.setViewVisibility(R.id.widgetProgress, View.GONE)
            views.setViewVisibility(R.id.widgetIcon, View.VISIBLE)

            // Icon & Background click -> Execute macro/command in background
            views.setOnClickPendingIntent(R.id.widgetIcon, clickPending)
            views.setOnClickPendingIntent(R.id.widgetRoot, clickPending)

            // Text label click -> Open app to macro section or remote folder!
            views.setOnClickPendingIntent(R.id.widgetText, openPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
