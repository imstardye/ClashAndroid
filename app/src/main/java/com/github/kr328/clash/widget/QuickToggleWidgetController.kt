package com.github.kr328.clash.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.widget.QuickToggleWidgetProvider.Companion.allWidgetIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object QuickToggleWidgetController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun requestUpdate(context: Context, widgetIds: IntArray? = null): Job {
        val appContext = context.applicationContext
        return scope.launch {
            try {
                renderWidgets(appContext, widgetIds)
            } catch (t: Throwable) {
                Log.w("Failed to update widget: $t", t)
            }
        }
    }

    private suspend fun renderWidgets(context: Context, widgetIds: IntArray?) {
        val ids = widgetIds ?: allWidgetIds(context)
        if (ids.isEmpty()) {
            return
        }

        val state = loadState(context)
        val manager = AppWidgetManager.getInstance(context)
        
        withContext(Dispatchers.Main.immediate) {
            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, state))
            }
        }
    }

    private suspend fun loadState(context: Context): WidgetState {
        val running = withContext(Dispatchers.IO) {
            try {
                StatusClient(context).currentProfile() != null
            } catch (t: Throwable) {
                Log.w("Failed to check status: $t", t)
                false
            }
        }

        return WidgetState(running = running)
    }

    private fun buildRemoteViews(context: Context, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_toggle)

        val statusText = if (state.running) {
            context.getString(R.string.widget_quick_toggle_status_active)
        } else {
            context.getString(R.string.widget_quick_toggle_status_inactive)
        }

        views.setTextViewText(R.id.widget_status_label, statusText)

        val statusColor = ContextCompat.getColor(
            context,
            if (state.running) {
                R.color.widget_quick_toggle_status_text_active
            } else {
                R.color.widget_quick_toggle_status_text_inactive
            }
        )
        views.setInt(R.id.widget_status_label, "setTextColor", statusColor)

        val iconTint = ContextCompat.getColor(
            context,
            if (state.running) {
                R.color.widget_quick_toggle_icon_active
            } else {
                R.color.widget_quick_toggle_icon_inactive
            }
        )
        views.setInt(R.id.widget_icon, "setColorFilter", iconTint)

        val rootBackground = if (state.running) {
            R.drawable.widget_quick_toggle_bg_active
        } else {
            R.drawable.widget_quick_toggle_bg
        }
        views.setInt(R.id.widget_root, "setBackgroundResource", rootBackground)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

        return views
    }

    private data class WidgetState(
        val running: Boolean
    )
}
