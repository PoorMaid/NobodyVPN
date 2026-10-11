package io.nekohasekai.sagernet.nobody

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.ItemNobodyServerBinding
import io.nekohasekai.sagernet.fmt.TypeMap
import moe.matsuri.nb4a.utils.toBytesString

class ServerListAdapter(
    private val onClick: (ProxyEntity) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.Holder>() {

    private val items = ArrayList<ProxyEntity>()
    private var selectedId: Long = -1L

    fun submit(list: List<ProxyEntity>, selected: Long) {
        items.clear()
        items.addAll(list)
        selectedId = selected
        notifyDataSetChanged()
    }

    fun setSelected(id: Long) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemNobodyServerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(val binding: ItemNobodyServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entity: ProxyEntity) {
            binding.name.text = entity.displayName()
            binding.proto.text = TypeMap.reversed[entity.type] ?: "-"
            when (entity.status) {
                NobodySubscriptions.STATUS_AVAILABLE -> {
                    binding.statusDot.setBackgroundResource(R.drawable.nobody_dot_available)
                    binding.ping.text = if (entity.ping > 0) "${entity.ping} ms" else "--"
                }
                NobodySubscriptions.STATUS_DEAD -> {
                    binding.statusDot.setBackgroundResource(R.drawable.nobody_dot_dead)
                    binding.ping.text = binding.root.context.getString(R.string.nobody_dead)
                }
                else -> {
                    binding.statusDot.setBackgroundResource(R.drawable.nobody_dot_unknown)
                    binding.ping.text = "--"
                }
            }
            binding.traffic.text = "↑${entity.tx.toBytesString()} ↓${entity.rx.toBytesString()}"
            val isSelected = entity.id == selectedId
            binding.name.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            binding.root.setOnClickListener { onClick(entity) }
        }
    }
}
