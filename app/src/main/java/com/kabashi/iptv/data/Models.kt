package com.kabashi.iptv.data

data class Credentials(
    val serverUrl: String,
    val username: String,
    val password: String
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
    val plot: String = ""
)

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
