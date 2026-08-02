package com.kabashi.iptv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Fast, tolerant native Media3 configuration for IPTV streams.
 *
 * It uses short live-TV buffers for faster channel changes, decoder fallback,
 * permissive MPEG-TS parsing and provider-friendly HTTP headers.
 */
object IptvPlayerFactory {
    private const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21 KABASHI-IPTV/1.4"

    @OptIn(UnstableApi::class)
    fun create(context: Context, subtitlesEnabled: Boolean = true): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity",
                    "Connection" to "keep-alive",
                    "Icy-MetaData" to "1"
                )
            )

        val tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(tsFlags)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory, extractorsFactory)
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setSelectUndeterminedTextLanguage(subtitlesEnabled)
                .build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1_000,
                8_000,
                350,
                700
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    fun alternateLiveUrl(url: String): String? {
        val question = url.indexOf('?')
        val path = if (question >= 0) url.substring(0, question) else url
        val query = if (question >= 0) url.substring(question) else ""
        return when {
            path.endsWith(".ts", ignoreCase = true) -> path.dropLast(3) + ".m3u8" + query
            path.endsWith(".m3u8", ignoreCase = true) -> path.dropLast(5) + ".ts" + query
            else -> null
        }
    }

    fun hlsFallbackUrl(url: String): String? = alternateLiveUrl(url)
}
