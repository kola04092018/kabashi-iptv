package com.kabashi.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kabashi.iptv.data.ContentType
import com.kabashi.iptv.data.MediaEntry
import com.kabashi.iptv.databinding.ItemChannelBinding
import com.kabashi.iptv.util.ImageLoader

class ChannelAdapter(
    private val onClick: (MediaEntry) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
    private val items = mutableListOf<MediaEntry>()

    fun submit(newItems: List<MediaEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun currentItems(): List<MediaEntry> = items.toList()

    inner class Holder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaEntry) {
            binding.channelName.text = item.name
            binding.catchUpLabel.text = when (item.type) {
                ContentType.LIVE -> if (item.hasCatchUp) "LIVE • CATCH-UP" else "LIVE"
                ContentType.VOD -> item.rating.takeIf { it.isNotBlank() }?.let { "MOVIE • ★ $it" } ?: "MOVIE"
                ContentType.SERIES -> item.rating.takeIf { it.isNotBlank() }?.let { "SERIES • ★ $it" } ?: "SERIES"
            }
            ImageLoader.load(binding.channelLogo, item.imageUrl)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f)
                    .setDuration(120).start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
}
