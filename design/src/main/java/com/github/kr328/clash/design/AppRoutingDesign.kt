package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.component.AccessControlMenu
import com.github.kr328.clash.design.databinding.DesignAppRoutingBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.AccessControlMode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AppRoutingDesign(
    context: Context,
    uiStore: UiStore,
    private val selected: MutableSet<String>,
    private var currentMode: AccessControlMode,
) : Design<AppRoutingDesign.Request>(context) {
    enum class Request {
        ReloadApps,
        SelectAll,
        SelectNone,
        SelectInvert,
        Import,
        Export,
    }

    private val binding = DesignAppRoutingBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppAdapter(context, selected)

    private val menu: AccessControlMenu by lazy {
        AccessControlMenu(context, binding.menuView, uiStore, requests)
    }

    val apps: List<AppInfo>
        get() = adapter.apps

    var mode: AccessControlMode
        get() = currentMode
        set(value) {
            currentMode = value
            updateModeUI()
        }

    override val root: View
        get() = binding.root

    suspend fun patchApps(apps: List<AppInfo>) {
        adapter.swapDataSet(adapter::apps, apps, false)
    }

    suspend fun rebindAll() {
        withContext(Dispatchers.Main) {
            adapter.rebindAll()
        }
    }

    private fun updateModeUI() {
        val isListEnabled = currentMode != AccessControlMode.AcceptAll
        
        binding.modeGroup.check(when (currentMode) {
            AccessControlMode.AcceptAll -> binding.modeAllowAll.id
            AccessControlMode.AcceptSelected -> binding.modeAllowSelected.id
            AccessControlMode.DenySelected -> binding.modeDenySelected.id
        })

        binding.modeDescription.text = when (currentMode) {
            AccessControlMode.AcceptAll -> context.getString(R.string.app_routing_mode_allow_all_desc)
            AccessControlMode.AcceptSelected -> context.getString(R.string.app_routing_mode_allow_selected_desc)
            AccessControlMode.DenySelected -> context.getString(R.string.app_routing_mode_deny_selected_desc)
        }

        binding.appListContainer.alpha = if (isListEnabled) 1.0f else 0.4f
        binding.searchView.isEnabled = isListEnabled
        binding.menuView.isEnabled = isListEnabled
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val newMode = when (checkedId) {
                binding.modeAllowAll.id -> AccessControlMode.AcceptAll
                binding.modeAllowSelected.id -> AccessControlMode.AcceptSelected
                binding.modeDenySelected.id -> AccessControlMode.DenySelected
                else -> return@addOnButtonCheckedListener
            }

            if (newMode != currentMode) {
                currentMode = newMode
                updateModeUI()
            }
        }

        binding.menuView.setOnClickListener {
            if (currentMode != AccessControlMode.AcceptAll) {
                menu.show()
            }
        }

        binding.searchView.setOnClickListener {
            if (currentMode != AccessControlMode.AcceptAll) {
                launch {
                    try {
                        requestSearch()
                    } finally {
                        withContext(NonCancellable) {
                            rebindAll()
                        }
                    }
                }
            }
        }

        updateModeUI()
    }

    private suspend fun requestSearch() {
        coroutineScope {
            val binding = DialogSearchBinding
                .inflate(context.layoutInflater, context.root, false)
            val adapter = AppAdapter(context, selected)
            val dialog = FullScreenDialog(context)
            val filter = Channel<Unit>(Channel.CONFLATED)

            dialog.setContentView(binding.root)

            binding.surface = dialog.surface
            binding.mainList.applyLinearAdapter(context, adapter)
            binding.keywordView.addTextChangedListener {
                filter.trySend(Unit)
            }
            binding.closeView.setOnClickListener {
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                cancel()
            }

            dialog.setOnShowListener {
                binding.keywordView.requestTextInput()
            }

            dialog.show()

            while (isActive) {
                filter.receive()

                val keyword = binding.keywordView.text?.toString() ?: ""

                val filteredApps: List<AppInfo> = if (keyword.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        this@AppRoutingDesign.apps.filter {
                            it.label.contains(keyword, ignoreCase = true) ||
                                    it.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                }

                adapter.patchDataSet(adapter::apps, filteredApps, false, AppInfo::packageName)

                delay(200)
            }
        }
    }
}
