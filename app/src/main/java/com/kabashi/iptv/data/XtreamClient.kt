package com.kabashi.iptv.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class XtreamClient(private val credentials: Credentials) {
    private val server = credentials.serverUrl.trim().trimEnd('/')

    suspend fun authenticate(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = getJsonObject(apiUrl())
            val userInfo = response.optJSONObject("user_info")
                ?: error("The server did not return Xtream user information.")
            val authenticated = userInfo.optIntFlexible("auth") == 1
            if (!authenticated) {
                error(userInfo.optString("message", "Login was rejected by the provider."))
            }
        }
    }

    suspend fun getAccountInfo(): AccountInfo = withContext(Dispatchers.IO) {
        val response = getJsonObject(apiUrl())
        val userInfo = response.optJSONObject("user_info")
            ?: error("The server did not return account information.")
        if (userInfo.optIntFlexible("auth") != 1) {
            error(userInfo.optString("message", "The provider rejected this account."))
        }
        AccountInfo(
            status = userInfo.optString("status", "Unknown"),
            expirationTimestamp = userInfo.optLongFlexible("exp_date"),
            activeConnections = userInfo.optIntFlexible("active_cons"),
            maxConnections = userInfo.optIntFlexible("max_connections")
        )
    }

    suspend fun getLiveCategories(): List<LiveCategory> =
        getCategories("get_live_categories", "All channels")

    suspend fun getVodCategories(): List<LiveCategory> =
        getCategories("get_vod_categories", "All movies")

    suspend fun getSeriesCategories(): List<LiveCategory> =
        getCategories("get_series_categories", "All series")

    private suspend fun getCategories(action: String, allLabel: String): List<LiveCategory> =
        withContext(Dispatchers.IO) {
            val array = getJsonArray(apiUrl(action))
            buildList {
                add(LiveCategory("", allLabel))
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        LiveCategory(
                            id = item.optString("category_id"),
                            name = item.optString("category_name", "Category")
                        )
                    )
                }
            }
        }

    suspend fun getLiveStreams(categoryId: String = ""): List<MediaEntry> = withContext(Dispatchers.IO) {
        val array = getJsonArray(apiUrl("get_live_streams", categoryParam(categoryId)))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optIntFlexible("stream_id")
                if (id <= 0) continue
                add(
                    MediaEntry(
                        id = id,
                        name = item.optString("name", "Channel $id"),
                        imageUrl = item.optString("stream_icon"),
                        categoryId = item.optString("category_id"),
                        type = ContentType.LIVE,
                        extension = "ts",
                        hasCatchUp = item.optIntFlexible("tv_archive") == 1,
                        catchUpDays = item.optIntFlexible("tv_archive_duration")
                    )
                )
            }
        }
    }

    suspend fun getVodStreams(categoryId: String = ""): List<MediaEntry> = withContext(Dispatchers.IO) {
        val array = getJsonArray(apiUrl("get_vod_streams", categoryParam(categoryId)))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optIntFlexible("stream_id")
                if (id <= 0) continue
                add(
                    MediaEntry(
                        id = id,
                        name = item.optString("name", "Movie $id"),
                        imageUrl = item.optString("stream_icon"),
                        categoryId = item.optString("category_id"),
                        type = ContentType.VOD,
                        extension = safeExtension(item.optString("container_extension", "mp4"), "mp4"),
                        rating = item.optString("rating"),
                        addedTimestamp = item.optLongFlexible("added")
                    )
                )
            }
        }
    }

    suspend fun getSeries(categoryId: String = ""): List<MediaEntry> = withContext(Dispatchers.IO) {
        val array = getJsonArray(apiUrl("get_series", categoryParam(categoryId)))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optIntFlexible("series_id")
                if (id <= 0) continue
                add(
                    MediaEntry(
                        id = id,
                        name = item.optString("name", "Series $id"),
                        imageUrl = item.optString("cover"),
                        categoryId = item.optString("category_id"),
                        type = ContentType.SERIES,
                        rating = item.optString("rating"),
                        plot = item.optString("plot"),
                        addedTimestamp = item.optLongFlexible("last_modified")
                            .takeIf { it > 0L } ?: item.optLongFlexible("added")
                    )
                )
            }
        }
    }

    suspend fun getSeriesEpisodes(seriesId: Int): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        val response = getJsonObject(apiUrl("get_series_info", mapOf("series_id" to seriesId.toString())))
        val episodes = response.optJSONObject("episodes") ?: JSONObject()
        val seasonKeys = mutableListOf<String>()
        val iterator = episodes.keys()
        while (iterator.hasNext()) seasonKeys.add(iterator.next())
        seasonKeys.sortWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

        buildList {
            for (seasonKey in seasonKeys) {
                val seasonNumber = seasonKey.toIntOrNull() ?: 0
                val list = episodes.optJSONArray(seasonKey) ?: continue
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optIntFlexible("id")
                    if (id <= 0) continue
                    val episodeNumber = item.optIntFlexible("episode_num").takeIf { it > 0 } ?: (i + 1)
                    add(
                        SeriesEpisode(
                            id = id,
                            title = item.optString("title", "Episode $episodeNumber"),
                            season = item.optIntFlexible("season").takeIf { it > 0 } ?: seasonNumber,
                            episodeNumber = episodeNumber,
                            extension = safeExtension(item.optString("container_extension", "mp4"), "mp4")
                        )
                    )
                }
            }
        }
    }

    suspend fun getCatchUp(streamId: Int): List<EpgItem> = withContext(Dispatchers.IO) {
        val response = getJsonObject(
            apiUrl("get_simple_data_table", mapOf("stream_id" to streamId.toString()))
        )
        val array = response.optJSONArray("epg_listings") ?: JSONArray()
        buildList {
            val nowSeconds = System.currentTimeMillis() / 1000L
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val startTs = item.optLongFlexible("start_timestamp")
                val stopTs = item.optLongFlexible("stop_timestamp")
                if (startTs <= 0L || stopTs <= startTs || startTs > nowSeconds) continue
                add(
                    EpgItem(
                        title = decodeBase64IfNeeded(item.optString("title", "Program")),
                        description = decodeBase64IfNeeded(item.optString("description")),
                        start = item.optString("start"),
                        end = item.optString("end"),
                        startTimestamp = startTs,
                        stopTimestamp = stopTs
                    )
                )
            }
        }.sortedByDescending { it.startTimestamp }
    }


    suspend fun getEpg(streamId: Int, limit: Int = 12): List<EpgItem> = withContext(Dispatchers.IO) {
        val response = getJsonObject(apiUrl("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString())))
        val array = response.optJSONArray("epg_listings") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val startTs = item.optLongFlexible("start_timestamp")
                val stopTs = item.optLongFlexible("stop_timestamp")
                if (startTs <= 0L || stopTs <= startTs) continue
                add(EpgItem(
                    title = decodeBase64IfNeeded(item.optString("title", "Program")),
                    description = decodeBase64IfNeeded(item.optString("description")),
                    start = item.optString("start"),
                    end = item.optString("end"),
                    startTimestamp = startTs,
                    stopTimestamp = stopTs
                ))
            }
        }.sortedBy { it.startTimestamp }
    }

    fun liveUrl(streamId: Int): String =
        "$server/live/${path(credentials.username)}/${path(credentials.password)}/$streamId.ts"

    fun vodUrl(streamId: Int, extension: String): String =
        "$server/movie/${path(credentials.username)}/${path(credentials.password)}/$streamId.${safeExtension(extension, "mp4")}" 

    fun seriesUrl(episodeId: Int, extension: String): String =
        "$server/series/${path(credentials.username)}/${path(credentials.password)}/$episodeId.${safeExtension(extension, "mp4")}" 

    fun catchUpUrl(streamId: Int, item: EpgItem): String {
        val rawStart = item.start
            .replace(" ", ":")
            .replace(Regex(":\\d{2}$"), "")
            .replace(Regex("^(\\d{4}-\\d{2}-\\d{2}):(\\d{2}):(\\d{2}).*"), "\$1:\$2-\$3")
        return "$server/timeshift/${path(credentials.username)}/${path(credentials.password)}/" +
            "${item.durationMinutes}/${Uri.encode(rawStart, ":-")}/$streamId.ts"
    }

    private fun categoryParam(categoryId: String): Map<String, String> =
        if (categoryId.isBlank()) emptyMap() else mapOf("category_id" to categoryId)

    private fun apiUrl(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val params = linkedMapOf(
            "username" to credentials.username,
            "password" to credentials.password
        )
        if (action != null) params["action"] = action
        params.putAll(extra)
        return "$server/player_api.php?" + params.entries.joinToString("&") {
            "${query(it.key)}=${query(it.value)}"
        }
    }

    private fun query(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun path(value: String): String = Uri.encode(value)

    private fun safeExtension(value: String, fallback: String): String =
        value.lowercase().trim().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: fallback

    private fun getJsonArray(url: String): JSONArray = JSONArray(httpGet(url))

    private fun getJsonObject(url: String): JSONObject = JSONObject(httpGet(url))

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "KABASHI-IPTV/1.6 FireTV")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Provider server returned HTTP $code${if (body.isNotBlank()) ": $body" else ""}")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optIntFlexible(key: String): Int = when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }

    private fun JSONObject.optLongFlexible(key: String): Long = when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun decodeBase64IfNeeded(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val decoded = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
            val text = String(decoded, Charsets.UTF_8)
            if (text.any { it.isLetterOrDigit() }) text else value
        }.getOrDefault(value)
    }
}
