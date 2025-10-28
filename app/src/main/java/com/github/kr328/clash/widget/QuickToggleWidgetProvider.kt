package com.github.kr328.clash.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import kotlinx.coroutines.Job

class QuickToggleWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        QuickToggleWidgetController.requestUpdate(context)
            .finishWhenComplete(goAsync())
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        QuickToggleWidgetController.requestUpdate(context, appWidgetIds)
            .finishWhenComplete(goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val job = when (intent.action) {
            ACTION_TOGGLE -> QuickToggleWidgetController.toggle(context)
            Intents.ACTION_CLASH_STARTED,
            Intents.ACTION_CLASH_STOPPED,
            Intents.ACTION_PROFILE_LOADED,
            Intents.ACTION_SERVICE_RECREATED -> QuickToggleWidgetController.requestUpdate(context)
            else -> null
        }

        if (job != null) {
            job.finishWhenComplete(goAsync())
        }

        super.onReceive(context, intent)
    }

    companion object {
        private const val PACKAGE = "com.github.kr328.clash.widget"
        const val ACTION_TOGGLE = "$PACKAGE.action.TOGGLE"

        fun allWidgetIds(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, QuickToggleWidgetProvider::class.java))
        }
    }
}

private fun Job.finishWhenComplete(pendingResult: BroadcastReceiver.PendingResult) {
    invokeOnCompletion { pendingResult.finish() }
}
