package com.kabashi.iptv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kabashi.iptv.R
import com.kabashi.iptv.databinding.ActivityMultiviewBinding
import com.kabashi.iptv.player.IptvPlayerFactory

class MultiViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMultiviewBinding
    private val players = mutableListOf<ExoPlayer>()
    private val playerViews = mutableListOf<PlayerView>()
    private val playbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty().take(4)
        val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty().take(4)
        if (urls.isEmpty()) {
            finish()
            return
        }

        binding.grid.columnCount = if (urls.size == 1) 1 else 2
        binding.grid.rowCount = if (urls.size <= 2) 1 else 2

        urls.forEachIndexed { index, url ->
            addTile(titles.getOrElse(index) { "Channel ${index + 1}" }, url, index)
        }
        playerViews.firstOrNull()?.requestFocus()
    }

    private fun addTile(title: String, initialUrl: String, index: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }
            setBackgroundResource(R.drawable.multiview_border)
        }
        val view = PlayerView(this).apply {
            useController = false
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { focusedView, focused ->
                if (focused) {
                    players.forEachIndexed { i, p -> p.volume = if (i == index) 1f else 0f }
                    focusedView.setBackgroundResource(R.drawable.focus_outline)
                } else {
                    focusedView.background = null
                }
            }
            setOnClickListener { requestFocus() }
        }
        val label = TextView(this).apply {
            text = title
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(14, 8, 14, 8)
        }
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM))
        binding.grid.addView(frame)

        val player = IptvPlayerFactory.create(this)
        playerViews += view
        players += player
        view.player = player
        player.volume = if (index == 0) 1f else 0f

        var currentUrl = initialUrl
        var fallbackAttempted = false
        fun start(url: String) {
            currentUrl = url
            val itemBuilder = MediaItem.Builder().setUri(url)
            when {
                url.contains(".m3u8", true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                url.contains(".mpd", true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                url.substringBefore('?').endsWith(".ts", true) -> itemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
            }
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(itemBuilder.build())
            player.prepare()
            player.playWhenReady = true

            if (!fallbackAttempted && IptvPlayerFactory.hlsFallbackUrl(url) != null) {
                playbackHandler.postDelayed({
                    if (fallbackAttempted || currentUrl != url) return@postDelayed
                    if (player.playbackState != Player.STATE_READY && !player.isPlaying) {
                        val fallback = IptvPlayerFactory.hlsFallbackUrl(url) ?: return@postDelayed
                        fallbackAttempted = true
                        start(fallback)
                    }
                }, 12_000L)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val fallback = if (!fallbackAttempted) {
                    IptvPlayerFactory.hlsFallbackUrl(currentUrl)
                } else {
                    null
                }
                if (fallback != null) {
                    fallbackAttempted = true
                    start(fallback)
                } else {
                    Toast.makeText(
                        this@MultiViewActivity,
                        "$title could not play (${error.errorCodeName}).",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        runCatching { start(initialUrl) }
            .onFailure {
                Toast.makeText(this, "A stream could not be opened in MultiView.", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        playbackHandler.removeCallbacksAndMessages(null)
        playerViews.forEach { it.player = null }
        players.forEach { it.release() }
        players.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLES = "titles"
        const val EXTRA_URLS = "urls"
    }
}
