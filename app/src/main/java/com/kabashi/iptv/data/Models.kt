package com.kabashi.iptv.data

data class Credentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class AccountInfo(
    val status: String,
    val expirationTimestamp: Long,
    val activeConnections: Int,
    val maxConnections: Int
)

data class LiveCategory(
    val id: String,
    val name: String
)

enum class ContentType {
    LIVE,
    VOD,
    SERIES
}

data class MediaEntry(
    val id: Int,
    val name: String,
    val imageUrl: String,
    val categoryId: String,
    val type: ContentType,
    val extension: String = "ts",
    val hasCatchUp: Boolean = false,
    val catchUpDays: Int = 0,
    val rating: String = "",
    val plot: String = "",
    val addedTimestamp: Long = 0L,
    val directSource: String = ""
)

data class PlaybackChannel(
    val id: Int,
    val name: String,
    val url: String,
    val hasCatchUp: Boolean
)

object PlaybackQueueStore {
    var channels: List<PlaybackChannel> = emptyList()
    var currentIndex: Int = 0

    fun clear() {
        channels = emptyList()
        currentIndex = 0
    }
}

data class SeriesEpisode(
    val id: Int,
    val title: String,
    val season: Int,
    val episodeNumber: Int,
    val extension: String
)

data class EpgItem(
    val title: String,
    val description: String,
    val start: String,
    val end: String,
    val startTimestamp: Long,
    val stopTimestamp: Long
) {
    val durationMinutes: Int
        get() = (((stopTimestamp - startTimestamp).coerceAtLeast(60L)) / 60L).toInt()
}
