package com.github.kr328.clash.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.github.kr328.clash.R
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.widget.QuickToggleWidgetProvider.Companion.allWidgetIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object QuickToggleWidgetController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

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

    fun stopRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun toggle(context: Context): Job {
        val appContext = context.applicationContext
        return scope.launch {
            try {
                toggleClash(appContext)
                delay(500)
                renderWidgets(appContext, null)
            } catch (t: Throwable) {
                Log.w("Failed to toggle from widget: $t", t)
            }
        }
    }

    private suspend fun renderWidgets(context: Context, widgetIds: IntArray?) {
        val ids = widgetIds ?: allWidgetIds(context)
        if (ids.isEmpty()) {
            stopRefresh()
            return
        }

        val state = loadState(context)
        val manager = AppWidgetManager.getInstance(context)
        
        withContext(Dispatchers.Main.immediate) {
            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, id, state))
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

    private suspend fun toggleClash(context: Context) {
        val running = withContext(Dispatchers.IO) {
            StatusClient(context).currentProfile() != null
        }

        if (running) {
            withContext(Dispatchers.IO) {
                context.stopClashService()
            }
        } else {
            val vpnRequest = withContext(Dispatchers.IO) {
                context.startClashService()
            }
            
            if (vpnRequest != null) {
                vpnRequest.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main.immediate) {
                    kotlin.runCatching {
                        ContextCompat.startActivity(context, vpnRequest, null)
                    }.onFailure {
                        Log.w("Unable to request VPN permission: $it", it)
                    }
                }
            }
        }
    }

    private fun buildRemoteViews(context: Context, widgetId: Int, state: WidgetState): RemoteViews {
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

        val subtitleText = if (state.running) {
            context.getString(R.string.widget_quick_toggle_tap_to_disconnect)
        } else {
            context.getString(R.string.widget_quick_toggle_tap_to_connect)
        }
        views.setTextViewText(R.id.widget_status_subtitle, subtitleText)

        if (state.running) {
            views.setViewVisibility(R.id.widget_glow_bg, View.VISIBLE)
            views.setViewVisibility(R.id.widget_pulse_ring_outer, View.VISIBLE)
            views.setViewVisibility(R.id.widget_pulse_ring_inner, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_glow_bg, View.GONE)
            views.setViewVisibility(R.id.widget_pulse_ring_outer, View.GONE)
            views.setViewVisibility(R.id.widget_pulse_ring_inner, View.GONE)
        }

        val iconTint = ContextCompat.getColor(
            context,
            if (state.running) {
                R.color.widget_quick_toggle_icon_active
            } else {
                R.color.widget_quick_toggle_icon_inactive
            }
        )
        views.setInt(R.id.widget_toggle_icon, "setColorFilter", iconTint)

        val buttonBackground = if (state.running) {
            R.drawable.widget_quick_toggle_button_bg_active
        } else {
            R.drawable.widget_quick_toggle_button_bg
        }
        views.setInt(R.id.widget_toggle_button, "setBackgroundResource", buttonBackground)

        val rootBackground = if (state.running) {
            R.drawable.widget_quick_toggle_bg_active
        } else {
            R.drawable.widget_quick_toggle_bg
        }
        views.setInt(R.id.widget_root, "setBackgroundResource", rootBackground)

        val contentDescription = if (state.running) {
            context.getString(R.string.widget_quick_toggle_content_stop)
        } else {
            context.getString(R.string.widget_quick_toggle_content_start)
        }
        views.setContentDescription(R.id.widget_toggle_button, contentDescription)

        val toggleIntent = Intent(context, QuickToggleWidgetProvider::class.java).apply {
            action = QuickToggleWidgetProvider.ACTION_TOGGLE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_button, togglePendingIntent)

        return views
    }

    private data class WidgetState(
        val running: Boolean
    )
}
