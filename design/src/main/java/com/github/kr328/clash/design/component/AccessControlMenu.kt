package com.github.kr328.clash.design.component

import android.content.Context
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.design.AccessControlQuickDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AppInfoSort
import com.github.kr328.clash.design.store.UiStore
import kotlinx.coroutines.channels.Channel

class AccessControlMenu(
    context: Context,
    menuView: View,
    private val uiStore: UiStore,
    private val requests: Channel<AccessControlQuickDesign.Request>,
) : PopupMenu.OnMenuItemClickListener {
    private val menu = PopupMenu(context, menuView)

    fun show() {
        menu.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.isCheckable)
            item.isChecked = !item.isChecked

        when (item.itemId) {
            R.id.select_all ->
                requests.trySend(AccessControlQuickDesign.Request.SelectAll)
            R.id.select_none ->
                requests.trySend(AccessControlQuickDesign.Request.SelectNone)
            R.id.select_invert ->
                requests.trySend(AccessControlQuickDesign.Request.SelectInvert)
            R.id.system_apps -> {
                uiStore.accessControlSystemApp = !item.isChecked

                requests.trySend(AccessControlQuickDesign.Request.ReloadApps)
            }
            R.id.name -> {
                uiStore.accessControlSort = AppInfoSort.Label

                requests.trySend(AccessControlQuickDesign.Request.ReloadApps)
            }
            R.id.package_name -> {
                uiStore.accessControlSort = AppInfoSort.PackageName

                requests.trySend(AccessControlQuickDesign.Request.ReloadApps)
            }
            R.id.install_time -> {
                uiStore.accessControlSort = AppInfoSort.InstallTime

                requests.trySend(AccessControlQuickDesign.Request.ReloadApps)
            }
            R.id.update_time -> {
                uiStore.accessControlSort = AppInfoSort.UpdateTime

                requests.trySend(AccessControlQuickDesign.Request.ReloadApps)
            }
            R.id.reverse -> {
                uiStore.accessControlReverse = item.isChecked

                requests.trySend(AccessControlQuickDesign.Request.ReloadApps)
            }
            R.id.import_from_clipboard -> {
                requests.trySend(AccessControlQuickDesign.Request.Import)
            }
            R.id.export_to_clipboard -> {
                requests.trySend(AccessControlQuickDesign.Request.Export)
            }
            else -> return false
        }

        return true
    }

    init {
        menu.menuInflater.inflate(R.menu.menu_access_control, menu.menu)

        when (uiStore.accessControlSort) {
            AppInfoSort.Label ->
                menu.menu.findItem(R.id.name).isChecked = true
            AppInfoSort.PackageName ->
                menu.menu.findItem(R.id.package_name).isChecked = true
            AppInfoSort.InstallTime ->
                menu.menu.findItem(R.id.install_time).isChecked = true
            AppInfoSort.UpdateTime ->
                menu.menu.findItem(R.id.update_time).isChecked = true
        }

        menu.menu.findItem(R.id.system_apps).isChecked = !uiStore.accessControlSystemApp
        menu.menu.findItem(R.id.reverse).isChecked = uiStore.accessControlReverse

        menu.setOnMenuItemClickListener(this)
    }
}