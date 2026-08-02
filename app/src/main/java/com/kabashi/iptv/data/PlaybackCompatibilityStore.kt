package com.kabashi.iptv.data

import android.content.Context

/** Remembers the URL/player combination that last reached a playable state for a channel. */
class PlaybackCompatibilityStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_compatibility_v1", Context.MODE_PRIVATE)

    fun preferredUrl(streamId: Int): String? =
        prefs.getString("url_$streamId", null)?.takeIf { it.isNotBlank() }

    fun savePreferredUrl(streamId: Int, url: String) {
        if (streamId > 0 && url.isNotBlank()) prefs.edit().putString("url_$streamId", url).apply()
    }

    fun clearPreferredUrl(streamId: Int) {
        if (streamId > 0) prefs.edit().remove("url_$streamId").apply()
    }
}
