package com.github.kr328.clash.design

import android.content.Context
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AlertDialog
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
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenAbout,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private val trafficHistory = ArrayDeque<Float>()
    private var lastForwardedBytes: Long? = null
    private var lastRunningState: Boolean? = null

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
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
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
