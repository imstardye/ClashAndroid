package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.AccessControlQuickDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AccessControlActivity : BaseActivity<AccessControlQuickDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            service.accessControlPackages.toMutableSet()
        }

        var currentMode = withContext(Dispatchers.IO) {
            service.accessControlMode
        }

        defer {
            withContext(Dispatchers.IO) {
                val changed = selected != service.accessControlPackages || currentMode != service.accessControlMode
                service.accessControlPackages = selected
                service.accessControlMode = currentMode
                if (clashRunning && changed) {
                    stopClashService()
                    while (clashRunning) {
                        delay(200)
                    }
                    startClashService()
                }
            }
        }

        val design = AccessControlQuickDesign(this, uiStore, selected, currentMode)

        setContentDesign(design)

        design.requests.send(AccessControlQuickDesign.Request.ReloadApps)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        AccessControlQuickDesign.Request.ReloadApps -> {
                            design.patchApps(loadApps(selected))
                            design.updateSelectedCount()
                        }

                        AccessControlQuickDesign.Request.SelectAll -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName)
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                            design.updateSelectedCount()
                        }

                        AccessControlQuickDesign.Request.SelectNone -> {
                            selected.clear()

                            design.rebindAll()
                            design.updateSelectedCount()
                        }

                        AccessControlQuickDesign.Request.SelectInvert -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName).toSet() - selected
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                            design.updateSelectedCount()
                        }

                        AccessControlQuickDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            val data = clipboard?.primaryClip

                            if (data != null && data.itemCount > 0) {
                                val packages = data.getItemAt(0).text.split("\n").toSet()
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                selected.clear()
                                selected.addAll(all)
                            }

                            design.rebindAll()
                            design.updateSelectedCount()
                        }

                        AccessControlQuickDesign.Request.Export -> {
                            val clipboard = getSystemService<ClipboardManager>()

                            val data = ClipData.newPlainText(
                                "packages",
                                selected.joinToString("\n")
                            )

                            clipboard?.setPrimaryClip(data)
                        }

                        is AccessControlQuickDesign.Request.ChangeMode -> {
                            currentMode = it.mode
                            if (it.mode == AccessControlMode.AcceptAll) {
                                design.updateSelectedCount()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort
            val systemApp = uiStore.accessControlSystemApp

            val base = compareByDescending<AppInfo> { it.packageName in selected }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true || it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                }
                .filter {
                    systemApp || !it.isSystemApp
                }
                .map {
                    it.toAppInfo(pm)
                }
                .sortedWith(comparator)
                .toList()
        }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }
}
