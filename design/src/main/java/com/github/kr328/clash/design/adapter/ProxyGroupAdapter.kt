package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.ItemProxyGroupBinding

class ProxyGroupAdapter(
    private val config: ProxyViewConfig,
    private val onToggle: (Int) -> Unit,
    private val onUrlTest: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyGroupAdapter.Holder>() {

    data class Group(
        val index: Int,
        val name: String,
        val adapter: ProxyAdapter,
        var type: Proxy.Type = Proxy.Type.Unknown,
        var expanded: Boolean = false,
        var urlTesting: Boolean = false,
        var count: Int = 0,
    )

    var groups: List<Group> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val recycledPool = RecyclerView.RecycledViewPool()

    inner class Holder(val binding: ItemProxyGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.proxyList.apply {
                layoutManager = GridLayoutManager(context, 6).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return when (config.proxyLine) {
                                1 -> 6
                                2 -> 3
                                3 -> 2
                                else -> 6
                            }
                        }
                    }
                }
                setRecycledViewPool(recycledPool)
                isNestedScrollingEnabled = false
                itemAnimator = null
            }

            binding.headerView.setOnClickListener {
                val position = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onToggle(groups[position].index)
            }

            binding.expandIcon.setOnClickListener {
                val position = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onToggle(groups[position].index)
            }

            binding.urlTestContainer.setOnClickListener {
                val position = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                val group = groups[position]

                if (!group.urlTesting && group.count > 0 && group.type.group) {
                    onUrlTest(group.index)
                }
            }
        }

        fun bind(group: Group) {
            binding.groupNameView.text = group.name

            val typeName = group.type.displayName(binding.root.context)
            binding.groupTypeChip.isVisible = typeName != null
            binding.groupTypeChip.text = typeName

            binding.groupCountChip.text = group.count.toString()
            binding.groupCountChip.isVisible = group.count > 0

            val canTest = group.type.group && group.count > 0
            binding.urlTestContainer.isVisible = canTest
            binding.urlTestContainer.isEnabled = canTest && !group.urlTesting
            binding.urlTestButton.isVisible = !group.urlTesting
            binding.urlTestButton.alpha = if (binding.urlTestContainer.isEnabled) 1f else 0.5f
            binding.urlTestProgress.isVisible = group.urlTesting

            binding.expandIcon.rotation = if (group.expanded) 180f else 0f
            binding.expandIcon.contentDescription = binding.root.context.getString(
                if (group.expanded) R.string.collapse else R.string.expand
            )
            binding.proxyList.isVisible = group.expanded

            if (binding.proxyList.adapter !== group.adapter) {
                binding.proxyList.adapter = group.adapter
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemProxyGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size
}

private fun Proxy.Type.displayName(context: Context): String? = when (this) {
    Proxy.Type.Selector -> context.getString(R.string.proxy_group_selector)
    Proxy.Type.Relay -> context.getString(R.string.proxy_group_relay)
    Proxy.Type.Fallback -> context.getString(R.string.proxy_group_fallback)
    Proxy.Type.URLTest -> context.getString(R.string.proxy_group_urltest)
    Proxy.Type.LoadBalance -> context.getString(R.string.proxy_group_loadbalance)
    else -> null
}
