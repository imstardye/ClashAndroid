package com.github.kr328.clash.design

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficTotalBytes
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.collections.ArrayDeque
import kotlin.math.max

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    sealed class Request {
        object ToggleStatus : Request()
        object OpenProxy : Request()
        object OpenProfiles : Request()
        object OpenProviders : Request()
        object OpenLogs : Request()
        object OpenSettings : Request()
        object OpenAbout : Request()
        object OpenAppRouting : Request()
        data class SetMode(val mode: TunnelState.Mode) : Request()
        data class SelectGlobalProxy(val name: String) : Request()

        companion object {
            @JvmStatic
            fun toggleStatus(): Request = ToggleStatus

            @JvmStatic
            fun openProxy(): Request = OpenProxy

            @JvmStatic
            fun openProfiles(): Request = OpenProfiles

            @JvmStatic
            fun openProviders(): Request = OpenProviders

            @JvmStatic
            fun openLogs(): Request = OpenLogs

            @JvmStatic
            fun openSettings(): Request = OpenSettings

            @JvmStatic
            fun openAbout(): Request = OpenAbout

            @JvmStatic
            fun openAppRouting(): Request = OpenAppRouting

            @JvmStatic
            fun setMode(mode: TunnelState.Mode): Request = SetMode(mode)

            @JvmStatic
            fun selectGlobalProxy(name: String): Request = SelectGlobalProxy(name)
        }
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private val trafficHistory = ArrayDeque<Float>()
    private var lastForwardedBytes: Long? = null
    private var lastRunningState: Boolean? = null
    private var currentMode: TunnelState.Mode? = null
    private var updatingModeToggle = false
    private var globalProxyItems: List<GlobalProxyItem> = emptyList()
    private var globalProxySelected: String? = null
    private val globalProxyAdapter = GlobalProxyDropdownAdapter()

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            if (lastRunningState != running) {
                lastRunningState = running
                resetTrafficIndicators(clearForwarded = running)
            }
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
            val forwardedText = binding.forwarded ?: context.getString(R.string.zero_traffic)
            binding.forwardedLabel = context.getString(R.string.traffic_total_label, forwardedText)

            val totalBytes = value.trafficTotalBytes()
            val previous = lastForwardedBytes
            lastForwardedBytes = totalBytes

            if (previous == null || !binding.clashRunning) {
                binding.trafficSpeedText = context.getString(R.string.traffic_speed_idle)
                if (previous == null && trafficHistory.isEmpty()) {
                    binding.hasTrafficHistory = false
                    binding.trafficChart.setSamples(emptyList())
                }
            } else {
                val delta = max(totalBytes - previous, 0L)
                binding.trafficSpeedText = context.getString(
                    R.string.traffic_speed,
                    Formatter.formatShortFileSize(context, delta)
                )
                addTrafficSample(delta)
            }

            binding.trafficChart.contentDescription =
                context.getString(
                    R.string.accessibility_traffic_chart,
                    binding.trafficSpeedText ?: context.getString(R.string.traffic_speed_idle)
                )
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            currentMode = mode

            binding.mode = modeLabelFor(mode)
            updateGlobalProxyDropdownState()
            updateGlobalProxyDropdownSelection()

            val buttonId = when (mode) {
                TunnelState.Mode.Direct -> binding.modeDirectButton.id
                TunnelState.Mode.Global -> binding.modeGlobalButton.id
                TunnelState.Mode.Rule -> binding.modeRuleButton.id
                else -> binding.modeRuleButton.id
            }

            updatingModeToggle = true
            binding.modeToggleGroup.check(buttonId)
            updatingModeToggle = false

            if (mode != TunnelState.Mode.Global) {
                binding.globalProxyDropdown.dismissDropDown()
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingModeToggle) {
                return@addOnButtonCheckedListener
            }

            val mode = when (checkedId) {
                binding.modeDirectButton.id -> TunnelState.Mode.Direct
                binding.modeGlobalButton.id -> TunnelState.Mode.Global
                binding.modeRuleButton.id -> TunnelState.Mode.Rule
                else -> return@addOnButtonCheckedListener
            }

            if (mode != currentMode) {
                requests.trySend(Request.SetMode(mode))
            }

        }

        binding.globalProxyDropdown.apply {
            setAdapter(globalProxyAdapter)
            keyListener = null
            setOnItemClickListener { _, _, position, _ ->
                val item = globalProxyItems.getOrNull(position) ?: return@setOnItemClickListener
                if (item.name != globalProxySelected) {
                    requests.trySend(Request.SelectGlobalProxy(item.name))
                }
            }
            setOnClickListener {
                if (binding.globalProxyDropdownLayout.isEnabled) {
                    showDropDown()
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && binding.globalProxyDropdownLayout.isEnabled) {
                    showDropDown()
                }
            }
        }

        updateGlobalProxyDropdownState()
        updateGlobalProxyDropdownSelection()

        val defaultForwarded = context.getString(R.string.zero_traffic)
        binding.clashRunning = false
        binding.forwarded = defaultForwarded
        binding.forwardedLabel = context.getString(R.string.traffic_total_label, defaultForwarded)
        binding.trafficSpeedText = context.getString(R.string.traffic_speed_idle)
        binding.hasTrafficHistory = false
        binding.trafficChart.setSamples(emptyList())
        binding.trafficChart.contentDescription =
            context.getString(
                R.string.accessibility_traffic_chart,
                binding.trafficSpeedText ?: context.getString(R.string.traffic_speed_idle)
            )
        lastRunningState = binding.clashRunning
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    suspend fun setGlobalProxyGroup(group: ProxyGroup?) {
        withContext(Dispatchers.Main) {
            val proxies = group?.proxies.orEmpty()

            globalProxyItems = proxies.map { it.asGlobalProxyItem() }
            globalProxySelected = group?.now
            globalProxyAdapter.notifyDataSetChanged()
            updateGlobalProxyDropdownState()
            updateGlobalProxyDropdownSelection()

            if (currentMode == TunnelState.Mode.Global) {
                binding.mode = modeLabelFor(currentMode)
                if (globalProxySelected.isNullOrBlank()) {
                    showGlobalProxyDropdownIfAvailable()
                }
            }
        }
    }

    suspend fun setGlobalProxySelection(name: String) {
        withContext(Dispatchers.Main) {
            globalProxySelected = name
            updateGlobalProxyDropdownSelection()

            if (currentMode == TunnelState.Mode.Global) {
                binding.mode = modeLabelFor(currentMode)
            }
        }
    }

    private fun updateGlobalProxyDropdownState() {
        val hasProxies = globalProxyItems.isNotEmpty()
        val isGlobalMode = currentMode == TunnelState.Mode.Global

        binding.globalProxyDropdownLayout.isVisible = hasProxies
        binding.globalProxyDropdownLayout.isEnabled = hasProxies && isGlobalMode
        binding.globalProxyDropdown.isEnabled = hasProxies && isGlobalMode

        if (!hasProxies) {
            binding.globalProxyDropdown.dismissDropDown()
        }
    }

    private fun updateGlobalProxyDropdownSelection() {
        val selected = globalProxyItems.firstOrNull { it.name == globalProxySelected }
        val display = selected?.displayTitle.orEmpty()

        binding.globalProxyDropdown.setText(display, false)
    }

    private fun showGlobalProxyDropdownIfAvailable() {
        if (!binding.globalProxyDropdownLayout.isEnabled || !binding.globalProxyDropdownLayout.isVisible) {
            return
        }

        binding.globalProxyDropdown.post {
            if (binding.globalProxyDropdownLayout.isEnabled && binding.globalProxyDropdownLayout.isVisible) {
                binding.globalProxyDropdown.showDropDown()
            }
        }
    }

    private fun Proxy.asGlobalProxyItem(): GlobalProxyItem {
        return GlobalProxyItem(name, title, subtitle)
    }

    private fun modeLabelFor(mode: TunnelState.Mode?): String {
        return when (mode) {
            TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
            TunnelState.Mode.Global -> {
                val selected = globalProxyItems.firstOrNull { it.name == globalProxySelected }
                val display = selected?.displayTitle?.takeIf { it.isNotBlank() }
                    ?: globalProxySelected

                if (!display.isNullOrBlank()) {
                    context.getString(R.string.global_mode_with_proxy, display)
                } else {
                    context.getString(R.string.global_mode)
                }
            }
            TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
            else -> context.getString(R.string.rule_mode)
        }
    }

    private data class GlobalProxyItem(
        val name: String,
        val title: String,
        val subtitle: String,
    ) {
        val displayTitle: String
            get() = title.takeIf { it.isNotBlank() } ?: name

        val displaySubtitle: String
            get() = when {
                subtitle.isNotBlank() -> subtitle
                title.isNotBlank() && title != name && name.isNotBlank() -> name
                else -> ""
            }
    }

    private inner class GlobalProxyDropdownAdapter : BaseAdapter(), Filterable {
        override fun getCount(): Int = globalProxyItems.size

        override fun getItem(position: Int): GlobalProxyItem = globalProxyItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as? TextView
                ?: LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            view.text = getItem(position).displayTitle
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.component_dropdown_item_two_line, parent, false)

            val item = getItem(position)
            val titleView = view.findViewById<TextView>(R.id.dropdownTitle)
            val subtitleView = view.findViewById<TextView>(R.id.dropdownSubtitle)

            titleView.text = item.displayTitle
            val subtitleText = item.displaySubtitle
            subtitleView.text = subtitleText
            subtitleView.isVisible = subtitleText.isNotEmpty()

            return view
        }

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    return FilterResults().apply {
                        values = globalProxyItems
                        count = globalProxyItems.size
                    }
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
        }
    }

    private fun resetTrafficIndicators(clearForwarded: Boolean) {
        trafficHistory.clear()
        lastForwardedBytes = null
        binding.hasTrafficHistory = false
        binding.trafficChart.setSamples(emptyList())
        binding.trafficSpeedText = context.getString(R.string.traffic_speed_idle)

        if (clearForwarded) {
            binding.forwarded = context.getString(R.string.zero_traffic)
        }

        val forwardedText = binding.forwarded ?: context.getString(R.string.zero_traffic)
        binding.forwardedLabel = context.getString(R.string.traffic_total_label, forwardedText)
        binding.trafficChart.contentDescription =
            context.getString(
                R.string.accessibility_traffic_chart,
                binding.trafficSpeedText ?: context.getString(R.string.traffic_speed_idle)
            )
    }

    private fun addTrafficSample(deltaBytes: Long) {
        val sample = deltaBytes.toFloat() / BYTES_IN_KIB
        trafficHistory.addLast(sample)

        while (trafficHistory.size > TRAFFIC_HISTORY_LIMIT) {
            trafficHistory.removeFirst()
        }

        binding.hasTrafficHistory = trafficHistory.isNotEmpty()
        binding.trafficChart.setSamples(trafficHistory.toList())
    }

    private companion object {
        private const val TRAFFIC_HISTORY_LIMIT = 30
        private const val BYTES_IN_KIB = 1024f
    }
}
