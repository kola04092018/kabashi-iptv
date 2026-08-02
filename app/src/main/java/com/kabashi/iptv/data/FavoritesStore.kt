package com.kabashi.iptv.data

import android.content.Context

class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("kola_favorites", Context.MODE_PRIVATE)

    private fun key(type: ContentType) = "favorites_${type.name.lowercase()}"

    fun isFavorite(item: MediaEntry): Boolean = ids(item.type).contains(item.id.toString())

    fun toggle(item: MediaEntry): Boolean {
        val values = ids(item.type).toMutableSet()
        val id = item.id.toString()
        val added = if (values.contains(id)) { values.remove(id); false } else { values.add(id); true }
        prefs.edit().putStringSet(key(item.type), values).apply()
        return added
    }

    fun filter(type: ContentType, items: List<MediaEntry>): List<MediaEntry> {
        val values = ids(type)
        return items.filter { values.contains(it.id.toString()) }
    }

    private fun ids(type: ContentType): Set<String> = prefs.getStringSet(key(type), emptySet())?.toSet() ?: emptySet()
}
