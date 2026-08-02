package com.kabashi.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kabashi.iptv.data.MediaEntry
import com.kabashi.iptv.databinding.ItemRecentMovieBinding
import com.kabashi.iptv.util.ImageLoader

class RecentMovieAdapter(
    private val onClick: (MediaEntry) -> Unit
) : RecyclerView.Adapter<RecentMovieAdapter.Holder>() {
    private val items = mutableListOf<MediaEntry>()

    fun submit(newItems: List<MediaEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class Holder(private val binding: ItemRecentMovieBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaEntry) {
            binding.movieName.text = item.name
            binding.movieMeta.text = item.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "NEW MOVIE"
            ImageLoader.load(binding.moviePoster, item.imageUrl)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnFocusChangeListener { view, focused ->
                view.animate()
                    .scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f)
                    .setDuration(100)
                    .start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemRecentMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
}
