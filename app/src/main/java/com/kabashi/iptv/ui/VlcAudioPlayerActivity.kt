package com.kabashi.iptv.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kabashi.iptv.data.PlaybackCompatibilityStore
import com.kabashi.iptv.data.PlaybackQueueStore
import com.kabashi.iptv.databinding.ActivityVlcAudioPlayerBinding
import com.kabashi.iptv.player.IptvPlayerFactory
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class VlcAudioPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVlcAudioPlayerBinding
    private lateinit var compatibility: PlaybackCompatibilityStore
    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val playbackHandler = Handler(Looper.getMainLooper())

    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var streamId = 0
    private var reachedPlaying = false
    private var channelIndex = 0
    private var channelTitle = "VLC Compatibility Mode"
    private var playGeneration = 0
    private var restartAttempts = 0
    private var bufferingSince = 0L

    private val hideControlsRunnable = Runnable {
        binding.vlcControls.visibility = View.GONE
    }

    private val showSpinnerRunnable = Runnable {
        if (!isFinishing && bufferingSince > 0L) {
            binding.vlcLoading.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVlcAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersiveMode()
        compatibility = PlaybackCompatibilityStore(this)

        val single = intent.getStringExtra(EXTRA_URL).orEmpty()
        val supplied = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        candidates = (supplied + single).map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
        val remembered = compatibility.preferredUrl(streamId)
        if (!remembered.isNullOrBlank() && remembered in candidates) {
            candidates = listOf(remembered) + candidates.filterNot { it == remembered }
        }

        channelIndex = PlaybackQueueStore.currentIndex.coerceIn(
            0,
            (PlaybackQueueStore.channels.size - 1).coerceAtLeast(0)
        )
        channelTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { "VLC Compatibility Mode" }
        binding.vlcTitle.text = channelTitle

        if (candidates.isEmpty()) {
            Toast.makeText(this, "No usable stream URL was provided.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        libVlc = LibVLC(
            this,
            arrayListOf(
                "--network-caching=2200",
                "--live-caching=2200",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--audio-time-stretch",
                "--drop-late-frames",
                "--skip-frames"
            )
        )

        vlcPlayer = MediaPlayer(libVlc).also { player ->
            player.attachViews(binding.vlcVideoLayout, null, false, false)
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Opening -> beginBuffering()
                    MediaPlayer.Event.Buffering -> {
                        if (event.buffering >= 95f) {
                            endBuffering()
                        } else {
                            beginBuffering()
                        }
                    }
                    MediaPlayer.Event.Playing -> {
                        reachedPlaying = true
                        restartAttempts = 0
                        endBuffering()
                        compatibility.savePreferredUrl(streamId, candidates[candidateIndex])
                        scheduleControlsHide()
                    }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> endBuffering()
                    MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                        endBuffering()
                        if (!tryNextCandidate()) {
                            Toast.makeText(
                                this,
                                "None of the available stream modes could play this channel.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        playCandidate(0)
        binding.vlcVideoLayout.setOnClickListener { toggleControls() }
    }

    private fun beginBuffering() {
        if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
        uiHandler.removeCallbacks(showSpinnerRunnable)
        // Avoid a spinner flash during very short network hiccups.
        uiHandler.postDelayed(showSpinnerRunnable, 450L)
    }

    private fun endBuffering() {
        bufferingSince = 0L
        uiHandler.removeCallbacks(showSpinnerRunnable)
        binding.vlcLoading.visibility = View.GONE
    }

    private fun playCandidate(index: Int, isRetry: Boolean = false) {
        if (index !in candidates.indices) return
        candidateIndex = index
        reachedPlaying = false
        if (!isRetry) restartAttempts = 0
        playGeneration += 1
        val generation = playGeneration

        beginBuffering()
        binding.vlcControls.visibility = View.VISIBLE
        binding.vlcTitle.text = "$channelTitle  •  Mode ${index + 1}/${candidates.size}  •  ↑/↓ Channel"

        val player = vlcPlayer ?: return
        player.stop()

        val media = Media(libVlc, Uri.parse(candidates[index])).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=2200")
            addOption(":live-caching=2200")
            addOption(":http-reconnect=true")
            addOption(":http-continuous=true")
            addOption(":http-user-agent=VLC/3.0.21 LibVLC/3.0.21 KABASHI-IPTV")
            addOption(":http-referrer=http://vpn.lion4k.vip/")
            addOption(":audio-track=-1")
            addOption(":avcodec-hw=any")
            addOption(":drop-late-frames")
            addOption(":skip-frames")
        }

        player.media = media
        media.release()
        player.play()

        playbackHandler.removeCallbacksAndMessages(null)
        playbackHandler.postDelayed({
            if (generation != playGeneration || reachedPlaying || isFinishing) return@postDelayed
            if (restartAttempts < 1) {
                restartAttempts += 1
                playCandidate(candidateIndex, isRetry = true)
            } else {
                tryNextCandidate()
            }
        }, 12_000L)
    }

    private fun tryNextCandidate(): Boolean {
        val next = candidateIndex + 1
        if (next >= candidates.size) return false
        playCandidate(next)
        return true
    }

    private fun buildChannelCandidates(url: String, id: Int): List<String> {
        val alternate = IptvPlayerFactory.alternateLiveUrl(url)
        val base = listOfNotNull(url, alternate)
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        val remembered = compatibility.preferredUrl(id)
        return if (!remembered.isNullOrBlank() && remembered in base) {
            listOf(remembered) + base.filterNot { it == remembered }
        } else {
            base
        }
    }

    private fun switchChannel(delta: Int) {
        val channels = PlaybackQueueStore.channels
        if (channels.size < 2) {
            Toast.makeText(this, "No other channels are available in this list.", Toast.LENGTH_SHORT).show()
            return
        }

        playGeneration += 1
        playbackHandler.removeCallbacksAndMessages(null)
        endBuffering()
        vlcPlayer?.stop()

        channelIndex = (channelIndex + delta + channels.size) % channels.size
        PlaybackQueueStore.currentIndex = channelIndex
        val channel = channels[channelIndex]
        streamId = channel.id
        channelTitle = channel.name
        candidates = buildChannelCandidates(channel.url, channel.id)
        candidateIndex = 0

        if (candidates.isEmpty()) {
            Toast.makeText(this, "No usable URL for ${channel.name}.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.vlcControls.visibility = View.VISIBLE
        playCandidate(0)
        Toast.makeText(this, channel.name, Toast.LENGTH_SHORT).show()
    }

    private fun toggleControls() {
        binding.vlcControls.visibility = if (binding.vlcControls.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
        if (binding.vlcControls.visibility == View.VISIBLE) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.postDelayed(hideControlsRunnable, 3_000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU -> {
                    toggleControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    switchChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    switchChannel(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!tryNextCandidate()) {
                        Toast.makeText(this, "No more stream modes.", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        playbackHandler.removeCallbacksAndMessages(null)
        endBuffering()
        vlcPlayer?.stop()
        vlcPlayer?.detachViews()
        vlcPlayer?.release()
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "vlc_url"
        const val EXTRA_URLS = "vlc_urls"
        const val EXTRA_TITLE = "vlc_title"
        const val EXTRA_STREAM_ID = "vlc_stream_id"
    }
}
