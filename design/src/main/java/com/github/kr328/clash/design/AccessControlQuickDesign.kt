package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.RadioButton
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.component.AccessControlQuickMenu
import com.github.kr328.clash.design.databinding.DesignAccessControlQuickBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.AccessControlMode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AccessControlQuickDesign(
    context: Context,
    uiStore: UiStore,
    private val selected: MutableSet<String>,
    private var currentMode: AccessControlMode,
) : Design<AccessControlQuickDesign.Request>(context) {
    sealed class Request {
        object ReloadApps : Request()
        object SelectAll : Request()
        object SelectNone : Request()
        object SelectInvert : Request()
        object Import : Request()
        object Export : Request()
        data class ChangeMode(val mode: AccessControlMode) : Request()
    }

    private val binding = DesignAccessControlQuickBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppAdapter(context, selected, ::notifySelectionChanged)

    private val menu: AccessControlQuickMenu by lazy {
        AccessControlQuickMenu(context, binding.menuView, uiStore, requests)
    }

    val apps: List<AppInfo>
        get() = adapter.apps

    val mode: AccessControlMode
        get() = currentMode

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

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.applyLinearAdapter(context, adapter)

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentMode = when (checkedId) {
                binding.modeAcceptAll.id -> AccessControlMode.AcceptAll
                binding.modeAcceptSelected.id -> AccessControlMode.AcceptSelected
                binding.modeDenySelected.id -> AccessControlMode.DenySelected
                else -> AccessControlMode.AcceptAll
            }
            updateUiState()
        }

        binding.selectedCount = selected.size
        binding.canSelectApps = canSelectApps()

        when (currentMode) {
            AccessControlMode.AcceptAll -> binding.modeAcceptAll.isChecked = true
            AccessControlMode.AcceptSelected -> binding.modeAcceptSelected.isChecked = true
            AccessControlMode.DenySelected -> binding.modeDenySelected.isChecked = true
        }

        binding.menuView.setOnClickListener {
            if (canSelectApps()) {
                menu.show()
            }
        }

        binding.searchView.setOnClickListener {
            if (canSelectApps()) {
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

        updateUiState()
    }

    private fun canSelectApps(): Boolean {
        return currentMode != AccessControlMode.AcceptAll
    }

    private fun updateUiState() {
        binding.canSelectApps = canSelectApps()
        binding.selectedCount = selected.size
    }

    suspend fun updateSelectedCount() {
        withContext(Dispatchers.Main) {
            binding.selectedCount = selected.size
        }
    }

    private fun notifySelectionChanged() {
        binding.selectedCount = selected.size
    }

    private suspend fun requestSearch() {
        coroutineScope {
            val binding = DialogSearchBinding
                .inflate(context.layoutInflater, context.root, false)
            val adapter = AppAdapter(context, selected, ::notifySelectionChanged)
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

                val apps: List<AppInfo> = if (keyword.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        apps.filter {
                            it.label.contains(keyword, ignoreCase = true) ||
                                    it.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                }

                adapter.patchDataSet(adapter::apps, apps, false, AppInfo::packageName)

                delay(200)
            }
        }
    }
}
