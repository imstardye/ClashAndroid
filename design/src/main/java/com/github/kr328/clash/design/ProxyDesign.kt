package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyGroupAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindInsets
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.invalidateChildren
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.swapDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    groupNames: List<String>,
    private val uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine

            groupAdapter.notifyDataSetChanged()
        }
    }

    private val groupItems = groupNames.mapIndexed { index, name ->
        ProxyGroupAdapter.Group(
            index = index,
            name = name,
            adapter = ProxyAdapter(config) { proxyName ->
                requests.trySend(Request.Select(index, proxyName))
            }
        )
    }.toMutableList()

    private val groupAdapter = ProxyGroupAdapter(
        config,
        ::toggleGroup,
        ::startUrlTesting,
    )

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        type: Proxy.Type,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        val viewStates = withContext(Dispatchers.Default) {
            proxies.map { proxy ->
                val link = if (proxy.type.group) links[proxy.name] else null

                ProxyViewState(config, proxy, parent, link)
            }
        }

        withContext(Dispatchers.Main) {
            val group = groupItems.getOrNull(position) ?: return@withContext
            val adapter = group.adapter

            adapter.selectable = type == Proxy.Type.Selector
            adapter.swapDataSet(adapter::states, viewStates, false)

            group.type = type
            group.count = viewStates.size
            group.urlTesting = false

            groupAdapter.notifyItemChanged(position)
        }
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            binding.groupsView.children.forEach { child ->
                val holder = binding.groupsView.getChildViewHolder(child) as? ProxyGroupAdapter.Holder ?: return@forEach

                holder.binding.proxyList.invalidateChildren()
            }
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }

        if (groupItems.isEmpty()) {
            groupAdapter.groups = groupItems

            binding.emptyView.isVisible = true
            binding.groupsView.isVisible = false
            binding.elevationView.isVisible = false
        } else {
            val initialIndex = groupItems.indexOfFirst { it.name == uiStore.proxyLastGroup }
                .takeIf { it >= 0 }
                ?: 0

            groupItems.getOrNull(initialIndex)?.expanded = true

            val toolbarHeight = context.getPixels(R.dimen.toolbar_height)

            groupAdapter.groups = groupItems

            binding.groupsView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = groupAdapter
                itemAnimator = null
                bindInsets(surface, toolbarHeight)
            }

            binding.emptyView.isVisible = false
            binding.groupsView.isVisible = true
            binding.elevationView.isVisible = true
        }
    }

    private fun toggleGroup(index: Int) {
        val group = groupItems.getOrNull(index) ?: return

        group.expanded = !group.expanded

        if (group.expanded) {
            uiStore.proxyLastGroup = group.name
        }

        groupAdapter.notifyItemChanged(index)
    }

    private fun startUrlTesting(index: Int) {
        val group = groupItems.getOrNull(index) ?: return

        if (group.urlTesting) {
            return
        }

        group.urlTesting = true
        groupAdapter.notifyItemChanged(index)

        requests.trySend(Request.UrlTest(index))
    }
}