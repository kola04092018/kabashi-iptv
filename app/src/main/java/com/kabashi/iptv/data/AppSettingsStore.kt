package com.kabashi.iptv.data

import android.content.Context

enum class LiveStreamMode {
    AUTO,
    MPEG_TS,
    HLS
}

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("kabashi_app_settings", Context.MODE_PRIVATE)

    var liveStreamMode: LiveStreamMode
        get() = runCatching {
            LiveStreamMode.valueOf(prefs.getString(KEY_STREAM_MODE, LiveStreamMode.AUTO.name).orEmpty())
        }.getOrDefault(LiveStreamMode.AUTO)
        set(value) = prefs.edit().putString(KEY_STREAM_MODE, value.name).apply()

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLES, value).apply()

    var autoHideControls: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HIDE_CONTROLS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HIDE_CONTROLS, value).apply()

    var compactInterface: Boolean
        get() = prefs.getBoolean(KEY_COMPACT_INTERFACE, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT_INTERFACE, value).apply()

    fun preferredLiveUrl(url: String): String = when (liveStreamMode) {
        LiveStreamMode.AUTO, LiveStreamMode.HLS -> toHls(url) ?: url
        LiveStreamMode.MPEG_TS -> toTs(url) ?: url
    }

    private fun toHls(url: String): String? {
        val queryIndex = url.indexOf('?')
        val path = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        if (!path.endsWith(".ts", ignoreCase = true)) return null
        return path.dropLast(3) + ".m3u8" + query
    }

    private fun toTs(url: String): String? {
        val queryIndex = url.indexOf('?')
        val path = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        if (!path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(5) + ".ts" + query
    }

    private companion object {
        const val KEY_STREAM_MODE = "live_stream_mode"
        const val KEY_SUBTITLES = "subtitles_enabled"
        const val KEY_AUTO_HIDE_CONTROLS = "auto_hide_controls"
        const val KEY_COMPACT_INTERFACE = "compact_interface"
    }
}
