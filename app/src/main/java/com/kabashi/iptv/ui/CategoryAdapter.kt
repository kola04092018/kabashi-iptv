package com.kabashi.iptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kabashi.iptv.data.LiveCategory
import com.kabashi.iptv.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onClick: (LiveCategory) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
    private val items = mutableListOf<LiveCategory>()
    private var selectedId = ""

    fun submit(newItems: List<LiveCategory>) {
        items.clear()
        items.addAll(newItems)
        selectedId = ""
        notifyDataSetChanged()
    }

    inner class Holder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LiveCategory) {
            binding.categoryName.text = item.name
            binding.root.isSelected = item.id == selectedId
            binding.root.setOnClickListener {
                selectedId = item.id
                notifyDataSetChanged()
                onClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
}
