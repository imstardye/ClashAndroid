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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object QuickToggleWidgetController {
    private const val UPDATE_INTERVAL = 8_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workChannel = Channel<WidgetWork>(Channel.UNLIMITED)
    private var processorJob: Job? = null
    private var periodicJob: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        ensureProcessor()
        ensurePeriodicJob(appContext)
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun requestUpdate(context: Context, widgetIds: IntArray? = null): Job {
        val appContext = context.applicationContext
        ensureProcessor()
        ensurePeriodicJob(appContext)
        return enqueueUpdate(appContext, widgetIds)
    }

    fun toggle(context: Context): Job {
        val appContext = context.applicationContext
        ensureProcessor()
        ensurePeriodicJob(appContext)
        return enqueueToggle(appContext)
    }

    private fun ensureProcessor() {
        if (processorJob?.isActive == true) {
            return
        }

        processorJob = scope.launch {
            for (work in workChannel) {
                try {
                    when (work) {
                        is WidgetWork.Update -> handleUpdate(work)
                        is WidgetWork.Toggle -> handleToggle(work)
                    }
                } catch (c: CancellationException) {
                    work.completion.completeExceptionally(c)
                    throw c
                } catch (t: Throwable) {
                    Log.w("Widget work failed: $t", t)
                    work.completion.completeExceptionally(t)
                }
            }
        }
    }

    private fun ensurePeriodicJob(context: Context) {
        if (periodicJob?.isActive == true) {
            return
        }

        val appContext = context.applicationContext
        periodicJob = scope.launch {
            while (isActive) {
                val completion = enqueueUpdate(appContext, null)
                try {
                    completion.await()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w("Periodic widget refresh failed: $t", t)
                }

                delay(UPDATE_INTERVAL)
            }
        }
    }

    private fun enqueueUpdate(context: Context, widgetIds: IntArray?): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        submitWork(WidgetWork.Update(context, widgetIds, completion))
        return completion
    }

    private fun enqueueToggle(context: Context): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        submitWork(WidgetWork.Toggle(context, completion))
        return completion
    }

    private fun submitWork(work: WidgetWork) {
        val result = workChannel.trySend(work)
        if (result.isSuccess) {
            return
        }

        if (result.isClosed) {
            work.completion.completeExceptionally(IllegalStateException("Widget work channel closed"))
            return
        }

        scope.launch {
            try {
                workChannel.send(work)
            } catch (c: CancellationException) {
                work.completion.completeExceptionally(c)
                throw c
            } catch (t: Throwable) {
                work.completion.completeExceptionally(t)
            }
        }
    }

    private suspend fun handleUpdate(work: WidgetWork.Update) {
        val success = renderWidgets(work.context, work.widgetIds)
        if (!success) {
            Log.i("Quick toggle widget fell back to placeholder state")
        }

        work.completion.complete(Unit)
    }

    private suspend fun handleToggle(work: WidgetWork.Toggle) {
        try {
            toggleClash(work.context)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            Log.w("Failed to toggle Clash from widget: $t", t)
            renderFallback(work.context, null)
            work.completion.completeExceptionally(t)
            return
        }

        delay(500)

        val success = renderWidgets(work.context, null)
        if (!success) {
            Log.i("Quick toggle widget refresh after toggle fell back to placeholder state")
        }

        work.completion.complete(Unit)
    }

    private suspend fun renderWidgets(context: Context, widgetIds: IntArray?): Boolean {
        val ids = widgetIds ?: allWidgetIds(context)
        if (ids.isEmpty()) {
            stop()
            return true
        }

        return try {
            val state = loadState(context)
            applyState(context, ids, state)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w("Failed to update quick toggle widget: $t", t)
            renderFallback(context, ids)
            false
        }
    }

    private suspend fun applyState(context: Context, ids: IntArray, state: WidgetState) {
        val manager = AppWidgetManager.getInstance(context)
        withContext(Dispatchers.Main.immediate) {
            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, id, state))
            }
        }
    }

    private suspend fun renderFallback(context: Context, widgetIds: IntArray?) {
        val ids = widgetIds ?: allWidgetIds(context)
        if (ids.isEmpty()) {
            return
        }

        val fallback = WidgetState(
            running = false,
            statusText = context.getString(R.string.widget_quick_toggle_status_error),
            uploadText = context.getString(R.string.widget_quick_toggle_placeholder),
            downloadText = context.getString(R.string.widget_quick_toggle_placeholder),
            isError = true
        )
        runCatching { applyState(context, ids, fallback) }
            .onFailure { Log.w("Failed to render fallback widget state: $it", it) }
    }

    private suspend fun toggleClash(context: Context) {
        val running = withContext(Dispatchers.IO) {
            StatusClient(context).currentProfile() != null
        }

        if (running) {
            withContext(Dispatchers.IO) { context.stopClashService() }
            return
        }

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

    private suspend fun loadState(context: Context): WidgetState {
        val profile = withContext(Dispatchers.IO) {
            StatusClient(context).currentProfile()
        }
        val running = profile != null

        val traffic = fetchTraffic(context)
        val upload = traffic?.trafficUpload()
        val download = traffic?.trafficDownload()

        val placeholder = context.getString(R.string.widget_quick_toggle_placeholder)
        val statusText = when {
            running && !profile.isNullOrBlank() -> profile
            running -> context.getString(R.string.widget_quick_toggle_status_active)
            else -> context.getString(R.string.widget_quick_toggle_status_inactive)
        }

        return WidgetState(
            running = running,
            statusText = statusText,
            uploadText = upload ?: placeholder,
            downloadText = download ?: placeholder,
            isError = false
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

        val bound = withContext(Dispatchers.Main.immediate) {
            kotlin.runCatching {
                appContext.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
            }.onFailure {
                Log.w("Unable to bind remote service: $it", it)
            }.getOrDefault(false)
        }

        if (!bound) {
            withContext(Dispatchers.Main.immediate) { appContext.unbindServiceSilent(connection) }
            return null
        }

        val remote = withTimeoutOrNull(2_000L) {
            deferred.await()
        }

        if (remote == null) {
            withContext(Dispatchers.Main.immediate) { appContext.unbindServiceSilent(connection) }
            Log.w("Remote service connection timed out")
            return null
        }

        return try {
            withContext(Dispatchers.IO) {
                remote.clash().block()
            }
        } catch (e: Exception) {
            Log.w("Failed to execute remote block: $e", e)
            null
        } finally {
            withContext(Dispatchers.Main.immediate) { appContext.unbindServiceSilent(connection) }
        }
    }

    private fun buildRemoteViews(context: Context, widgetId: Int, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_toggle)

        views.setTextViewText(R.id.widget_status_label, state.statusText)
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

        val isRunning = state.running
        val buttonBackground = if (isRunning) {
            R.drawable.widget_quick_toggle_button_bg_active
        } else {
            R.drawable.widget_quick_toggle_button_bg
        }
        val rootBackground = if (isRunning) {
            R.drawable.widget_quick_toggle_bg_active
        } else {
            R.drawable.widget_quick_toggle_bg
        }

        views.setInt(R.id.widget_root, "setBackgroundResource", rootBackground)
        views.setInt(R.id.widget_button_container, "setBackgroundResource", buttonBackground)

        val iconTint = ContextCompat.getColor(
            context,
            if (isRunning) R.color.widget_quick_toggle_icon_active else R.color.widget_quick_toggle_icon_inactive
        )
        views.setInt(R.id.widget_toggle_icon, "setColorFilter", iconTint)

        val contentDescription = when {
            state.isError -> context.getString(R.string.widget_quick_toggle_content_refresh)
            isRunning -> context.getString(R.string.widget_quick_toggle_content_stop)
            else -> context.getString(R.string.widget_quick_toggle_content_start)
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
        val statusText: CharSequence,
        val uploadText: CharSequence,
        val downloadText: CharSequence,
        val isError: Boolean
    )

    private sealed interface WidgetWork {
        val context: Context
        val completion: CompletableDeferred<Unit>

        data class Update(
            override val context: Context,
            val widgetIds: IntArray?,
            override val completion: CompletableDeferred<Unit>
        ) : WidgetWork

        data class Toggle(
            override val context: Context,
            override val completion: CompletableDeferred<Unit>
        ) : WidgetWork
    }
}
