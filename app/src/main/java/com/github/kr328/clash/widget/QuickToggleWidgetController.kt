package com.github.kr328.clash.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.unbindServiceSilent
import com.github.kr328.clash.widget.QuickToggleWidgetProvider.Companion.ACTION_TOGGLE
import com.github.kr328.clash.widget.QuickToggleWidgetProvider.Companion.allWidgetIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object QuickToggleWidgetController {
    private const val UPDATE_INTERVAL = 8_000L
    private val updateMutex = Mutex()
    private var periodicJob: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (periodicJob == null) {
            periodicJob = Global.launch {
                while (true) {
                    requestUpdate(appContext)
                    delay(UPDATE_INTERVAL)
                }
            }
        }

        requestUpdate(appContext)
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun requestUpdate(context: Context, widgetIds: IntArray? = null) {
        val appContext = context.applicationContext
        Global.launch {
            updateMutex.withLock {
                updateWidgets(appContext, widgetIds)
            }
        }
    }

    fun toggle(context: Context) {
        val appContext = context.applicationContext
        Global.launch {
            val running = StatusClient(appContext).currentProfile() != null

            if (running) {
                appContext.stopClashService()
            } else {
                val vpnRequest = appContext.startClashService()

                if (vpnRequest != null) {
                    vpnRequest.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    kotlin.runCatching {
                        ContextCompat.startActivity(appContext, vpnRequest, null)
                    }.onFailure {
                        Log.w("Unable to request VPN permission: $it", it)
                    }
                }
            }

            delay(500)
            requestUpdate(appContext)
        }
    }

    private suspend fun updateWidgets(context: Context, widgetIds: IntArray?) {
        val ids = widgetIds ?: allWidgetIds(context)
        if (ids.isEmpty()) return

        val state = loadState(context)
        val manager = AppWidgetManager.getInstance(context)

        ids.forEach { id ->
            manager.updateAppWidget(id, buildRemoteViews(context, id, state))
        }
    }

    private suspend fun loadState(context: Context): WidgetState {
        val profile = StatusClient(context).currentProfile()
        val running = profile != null

        val traffic = fetchTraffic(context)
        val upload = traffic?.trafficUpload()
        val download = traffic?.trafficDownload()

        val placeholder = context.getString(R.string.widget_quick_toggle_placeholder)

        return WidgetState(
            running = running,
            profileName = profile,
            uploadText = upload ?: placeholder,
            downloadText = download ?: placeholder
        )
    }

    private suspend fun fetchTraffic(context: Context): Long? {
        return withRemoteClash(context) {
            try {
                queryTrafficTotal()
            } catch (e: DeadObjectException) {
                Log.w("Remote service died while querying traffic", e)
                null
            }
        }
    }

    private suspend fun <T> withRemoteClash(
        context: Context,
        block: suspend IClashManager.() -> T
    ): T? {
        val appContext = context.applicationContext
        val deferred = CompletableDeferred<IRemoteService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                kotlin.runCatching {
                    deferred.complete(service.unwrap(IRemoteService::class))
                }.onFailure {
                    deferred.completeExceptionally(it)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("Service disconnected"))
                }
            }
        }

        val bound = kotlin.runCatching {
            appContext.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w("Unable to bind remote service: $it", it)
        }.getOrDefault(false)

        if (!bound) {
            appContext.unbindServiceSilent(connection)
            return null
        }

        val remote = withTimeoutOrNull(2_000L) {
            deferred.await()
        }

        if (remote == null) {
            appContext.unbindServiceSilent(connection)
            Log.w("Remote service connection timed out")
            return null
        }

        return try {
            remote.clash().block()
        } catch (e: Exception) {
            Log.w("Failed to execute remote block: $e", e)
            null
        } finally {
            appContext.unbindServiceSilent(connection)
        }
    }

    private fun buildRemoteViews(context: Context, widgetId: Int, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_toggle)

        val statusText = when {
            state.running && !state.profileName.isNullOrBlank() -> state.profileName
            state.running -> context.getString(R.string.widget_quick_toggle_status_active)
            else -> context.getString(R.string.widget_quick_toggle_status_inactive)
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
        views.setTextViewText(R.id.widget_upload_label, context.getString(R.string.widget_quick_toggle_upload_label))
        views.setTextViewText(R.id.widget_download_label, context.getString(R.string.widget_quick_toggle_download_label))
        views.setTextViewText(R.id.widget_upload_value, state.uploadText)
        views.setTextViewText(R.id.widget_download_value, state.downloadText)

        val buttonBackground = if (state.running) {
            R.drawable.widget_quick_toggle_button_bg_active
        } else {
            R.drawable.widget_quick_toggle_button_bg
        }
        val rootBackground = if (state.running) {
            R.drawable.widget_quick_toggle_bg_active
        } else {
            R.drawable.widget_quick_toggle_bg
        }

        views.setInt(R.id.widget_root, "setBackgroundResource", rootBackground)
        views.setInt(R.id.widget_button_container, "setBackgroundResource", buttonBackground)

        val iconTint = ContextCompat.getColor(
            context,
            if (state.running) R.color.widget_quick_toggle_icon_active else R.color.widget_quick_toggle_icon_inactive
        )
        views.setInt(R.id.widget_toggle_icon, "setColorFilter", iconTint)

        val contentDescription = if (state.running) {
            context.getString(R.string.widget_quick_toggle_content_stop)
        } else {
            context.getString(R.string.widget_quick_toggle_content_start)
        }
        views.setContentDescription(R.id.widget_button_container, contentDescription)

        val toggleIntent = Intent(context, QuickToggleWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_button_touch, togglePendingIntent)

        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            widgetId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

        return views
    }

    private data class WidgetState(
        val running: Boolean,
        val profileName: String?,
        val uploadText: String,
        val downloadText: String
    )
}
