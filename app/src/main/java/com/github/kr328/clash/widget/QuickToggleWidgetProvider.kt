package com.github.kr328.clash.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents

class QuickToggleWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        QuickToggleWidgetController.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        QuickToggleWidgetController.stop()
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        QuickToggleWidgetController.requestUpdate(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE -> QuickToggleWidgetController.toggle(context)
            Intents.ACTION_CLASH_STARTED,
            Intents.ACTION_CLASH_STOPPED,
            Intents.ACTION_PROFILE_LOADED,
            Intents.ACTION_SERVICE_RECREATED -> QuickToggleWidgetController.requestUpdate(context)
        }
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
