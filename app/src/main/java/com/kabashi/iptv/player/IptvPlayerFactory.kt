package com.kabashi.iptv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Shared player configuration for IPTV streams.
 *
 * Many Xtream providers return MPEG-TS feeds that omit access-unit delimiters or
 * IDR keyframes, use redirects between HTTP and HTTPS, or reject generic Android
 * user agents. This configuration makes Media3 more tolerant of those streams.
 */
object IptvPlayerFactory {
    private const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
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

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    fun hlsFallbackUrl(url: String): String? {
        val question = url.indexOf('?')
        val path = if (question >= 0) url.substring(0, question) else url
        val query = if (question >= 0) url.substring(question) else ""
        if (!path.endsWith(".ts", ignoreCase = true)) return null
        return path.dropLast(3) + ".m3u8" + query
    }
}
