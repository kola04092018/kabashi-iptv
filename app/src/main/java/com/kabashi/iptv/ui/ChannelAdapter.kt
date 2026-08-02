package com.kabashi.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kabashi.iptv.data.ContentType
import com.kabashi.iptv.data.MediaEntry
import com.kabashi.iptv.databinding.ItemChannelBinding
import com.kabashi.iptv.util.ImageLoader
import java.text.DateFormat
import java.util.Date

class ChannelAdapter(
    private val onClick: (MediaEntry) -> Unit,
    private val onLongClick: ((MediaEntry) -> Unit)? = null,
    private val isFavorite: ((MediaEntry) -> Boolean)? = null
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
    private val items = mutableListOf<MediaEntry>()
    fun submit(newItems: List<MediaEntry>) { items.clear(); items.addAll(newItems); notifyDataSetChanged() }
    fun currentItems(): List<MediaEntry> = items.toList()

    inner class Holder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaEntry, position: Int) {
            binding.channelNumber.text = (position + 1).toString()
            val favorite = isFavorite?.invoke(item) == true
            binding.channelName.text = item.name
            binding.favoriteStar.visibility = if (favorite) View.VISIBLE else View.GONE
            binding.catchUpLabel.text = when (item.type) {
                ContentType.LIVE -> if (item.hasCatchUp) "LIVE  •  CATCH-UP" else "LIVE"
                ContentType.VOD -> buildString {
                    append("MOVIE")
                    if (item.rating.isNotBlank()) append("  •  ★ ${item.rating}")
                    if (item.addedTimestamp > 0L) append("  •  ADDED " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.addedTimestamp * 1000L)))
                }
                ContentType.SERIES -> item.rating.takeIf { it.isNotBlank() }?.let { "SERIES  •  ★ $it" } ?: "SERIES"
            }
            ImageLoader.load(binding.channelLogo, item.imageUrl)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick?.invoke(item); onLongClick != null }
            binding.root.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.015f else 1f).scaleY(if (focused) 1.015f else 1f).setDuration(90).start()
            }
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position)
    override fun getItemCount() = items.size
}
