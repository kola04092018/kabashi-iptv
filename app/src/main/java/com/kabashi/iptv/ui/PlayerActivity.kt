package com.kabashi.iptv.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kabashi.iptv.data.EpgItem
import com.kabashi.iptv.data.SecureCredentialStore
import com.kabashi.iptv.data.XtreamClient
import com.kabashi.iptv.databinding.ActivityPlayerBinding
import com.kabashi.iptv.player.IptvPlayerFactory
import com.kabashi.iptv.recording.RecordingService
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var client: XtreamClient
    private var player: ExoPlayer? = null
    private var streamId = 0
    private var streamName = "Live channel"
    private var liveUrl = ""
    private var hasCatchUp = false
    private var allowRecording = true
    private var currentTitle = ""
    private var currentIsLive = false
    private var hlsFallbackAttempted = false
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var playbackGeneration = 0

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecordingNow()
        } else {
            Toast.makeText(this, "Notification permission is required while recording.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val credentials = SecureCredentialStore(this).load()
        if (credentials == null) {
            finish()
            return
        }
        client = XtreamClient(credentials)
        streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
        streamName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Live channel" }
        hasCatchUp = intent.getBooleanExtra(EXTRA_CATCH_UP, false)
        allowRecording = intent.getBooleanExtra(EXTRA_ALLOW_RECORDING, true)
        liveUrl = intent.getStringExtra(EXTRA_DIRECT_URL).orEmpty().ifBlank { client.liveUrl(streamId) }

        binding.title.text = streamName
        binding.catchUpButton.visibility = if (hasCatchUp) View.VISIBLE else View.GONE
        binding.liveButton.visibility = View.GONE
        binding.recordButton.visibility = if (allowRecording) View.VISIBLE else View.GONE
        binding.stopRecordingButton.visibility = if (allowRecording) View.VISIBLE else View.GONE

        binding.externalButton.setOnClickListener { openExternalPlayer(currentUrl()) }
        binding.recordButton.setOnClickListener { startRecording() }
        binding.stopRecordingButton.setOnClickListener { RecordingService.stop(this) }
        binding.catchUpButton.setOnClickListener { showCatchUp() }
        binding.liveButton.setOnClickListener { play(liveUrl, streamName, true) }
        binding.pipButton.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE
        binding.pipButton.setOnClickListener { enterPip() }

        initializePlayer()
        play(liveUrl, streamName, true)
    }

    private fun initializePlayer() {
        player = IptvPlayerFactory.create(this).also { exo ->
            binding.playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    binding.buffering.visibility =
                        if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    val fallback = if (currentIsLive && !hlsFallbackAttempted) {
                        IptvPlayerFactory.hlsFallbackUrl(currentUrl())
                    } else {
                        null
                    }
                    if (fallback != null) {
                        hlsFallbackAttempted = true
                        Toast.makeText(
                            this@PlayerActivity,
                            "Trying the provider's HLS stream mode…",
                            Toast.LENGTH_SHORT
                        ).show()
                        playInternal(fallback, currentTitle, true)
                        return
                    }

                    binding.buffering.visibility = View.GONE
                    Toast.makeText(
                        this@PlayerActivity,
                        "Built-in player error: ${error.errorCodeName}. Try External Player for an unsupported codec.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    private fun play(url: String, title: String, live: Boolean) {
        hlsFallbackAttempted = false
        playInternal(url, title, live)
    }

    private fun playInternal(url: String, title: String, live: Boolean) {
        currentTitle = title
        currentIsLive = live
        binding.playerView.tag = url
        binding.title.text = title
        binding.liveButton.visibility = if (live) View.GONE else View.VISIBLE
        binding.buffering.visibility = View.VISIBLE

        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(url)
        when {
            url.contains(".m3u8", true) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", true) -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            url.substringBefore('?').endsWith(".ts", true) -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }

        val generation = ++playbackGeneration
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(builder.build())
            prepare()
            playWhenReady = true
        }

        if (live && IptvPlayerFactory.hlsFallbackUrl(url) != null) {
            playbackHandler.postDelayed({
                val exo = player ?: return@postDelayed
                if (generation != playbackGeneration || hlsFallbackAttempted) return@postDelayed
                if (exo.playbackState != Player.STATE_READY && !exo.isPlaying) {
                    val fallback = IptvPlayerFactory.hlsFallbackUrl(url) ?: return@postDelayed
                    hlsFallbackAttempted = true
                    Toast.makeText(
                        this,
                        "The TS stream is still buffering. Trying HLS mode…",
                        Toast.LENGTH_SHORT
                    ).show()
                    playInternal(fallback, title, true)
                }
            }, 12_000L)
        }
    }

    private fun currentUrl(): String = binding.playerView.tag as? String ?: liveUrl

    private fun openExternalPlayer(url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with external player"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No external video player is installed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRecording() {
        if (currentUrl().contains(".m3u8", ignoreCase = true)) {
            Toast.makeText(
                this,
                "Recording currently supports direct non-DRM transport streams.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startRecordingNow()
        }
    }

    private fun startRecordingNow() {
        RecordingService.start(this, currentUrl(), binding.title.text.toString())
        Toast.makeText(this, "Recording started.", Toast.LENGTH_SHORT).show()
    }

    private fun showCatchUp() {
        binding.buffering.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { client.getCatchUp(streamId) }
                .onSuccess { items ->
                    binding.buffering.visibility = View.GONE
                    if (items.isEmpty()) {
                        Toast.makeText(this@PlayerActivity, "No catch-up programs were returned.", Toast.LENGTH_LONG).show()
                    } else {
                        showCatchUpDialog(items.take(150))
                    }
                }
                .onFailure {
                    binding.buffering.visibility = View.GONE
                    Toast.makeText(this@PlayerActivity, it.message ?: "Catch-up failed.", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showCatchUpDialog(items: List<EpgItem>) {
        val labels = items.map {
            val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(it.startTimestamp * 1000L))
            "$whenText  •  ${it.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Catch-up — $streamName")
            .setItems(labels) { _, which ->
                val item = items[which]
                play(client.catchUpUrl(streamId, item), item.title, false)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        playbackHandler.removeCallbacksAndMessages(null)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_NAME = "stream_name"
        const val EXTRA_CATCH_UP = "catch_up"
        const val EXTRA_CATCH_UP_DAYS = "catch_up_days"
        const val EXTRA_DIRECT_URL = "direct_url"
        const val EXTRA_ALLOW_RECORDING = "allow_recording"
    }
}
