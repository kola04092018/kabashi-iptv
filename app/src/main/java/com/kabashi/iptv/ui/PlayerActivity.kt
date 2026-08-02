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
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.kabashi.iptv.data.AppSettingsStore
import com.kabashi.iptv.data.EpgItem
import com.kabashi.iptv.data.LiveStreamMode
import com.kabashi.iptv.data.PlaybackQueueStore
import com.kabashi.iptv.data.PlaybackCompatibilityStore
import com.kabashi.iptv.data.SecureCredentialStore
import com.kabashi.iptv.data.XtreamClient
import com.kabashi.iptv.databinding.ActivityPlayerBinding
import com.kabashi.iptv.player.IptvPlayerFactory
import com.kabashi.iptv.recording.RecordingService
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var client: XtreamClient
    private lateinit var settings: AppSettingsStore
    private lateinit var compatibility: PlaybackCompatibilityStore
    private var player: ExoPlayer? = null
    private var streamId = 0
    private var streamName = "Channel"
    private var liveUrl = ""
    private var hasCatchUp = false
    private var allowRecording = true
    private var currentTitle = ""
    private var currentIsLive = false
    private var hlsFallbackAttempted = false
    private var streamCandidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var reachedReady = false
    private var subtitlesEnabled = true
    private var controlsVisible = true
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var playbackGeneration = 0
    private val infoBarHandler = Handler(Looper.getMainLooper())

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecordingNow()
        else Toast.makeText(this, "Notification permission is required while recording.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersiveMode()

        val credentials = SecureCredentialStore(this).load()
        if (credentials == null) {
            finish()
            return
        }
        client = XtreamClient(credentials)
        settings = AppSettingsStore(this)
        compatibility = PlaybackCompatibilityStore(this)
        subtitlesEnabled = settings.subtitlesEnabled

        streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
        streamName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Channel" }
        hasCatchUp = intent.getBooleanExtra(EXTRA_CATCH_UP, false)
        allowRecording = intent.getBooleanExtra(EXTRA_ALLOW_RECORDING, true)
        currentIsLive = intent.getBooleanExtra(EXTRA_IS_LIVE, true)
        val directUrl = intent.getStringExtra(EXTRA_DIRECT_URL).orEmpty()
            .ifBlank { client.liveUrl(streamId) }
        liveUrl = if (currentIsLive) settings.preferredLiveUrl(directUrl) else directUrl
        streamCandidates = buildStreamCandidates(liveUrl)
        compatibility.preferredUrl(streamId)?.let { remembered ->
            if (remembered in streamCandidates) {
                streamCandidates = listOf(remembered) + streamCandidates.filterNot { it == remembered }
            }
        }

        binding.title.text = streamName
        binding.channelHint.text = if (currentIsLive && PlaybackQueueStore.channels.size > 1) {
            "UP / DOWN: change channel"
        } else {
            "OK: show controls"
        }
        binding.catchUpButton.visibility = if (hasCatchUp) View.VISIBLE else View.GONE
        binding.liveButton.visibility = View.GONE
        binding.recordButton.visibility = if (allowRecording) View.VISIBLE else View.GONE
        binding.stopRecordingButton.visibility = if (allowRecording) View.VISIBLE else View.GONE
        binding.subtitleButton.text = if (subtitlesEnabled) "SUBTITLES ON" else "SUBTITLES OFF"
        binding.audioFixButton.text = "AUDIO TRACK"

        binding.externalButton.setOnClickListener { openExternalPlayer(currentUrl()) }
        binding.audioFixButton.setOnClickListener { showAudioTrackMenu() }
        binding.vlcAudioButton.setOnClickListener { openVlcAudioPlayer() }
        binding.subtitleButton.setOnClickListener { toggleSubtitles() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.recordButton.setOnClickListener { startRecording() }
        binding.stopRecordingButton.setOnClickListener { RecordingService.stop(this) }
        binding.catchUpButton.setOnClickListener { showCatchUp() }
        binding.liveButton.setOnClickListener { play(liveUrl, streamName, true) }
        binding.pipButton.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE
        binding.pipButton.setOnClickListener { enterPip() }
        binding.playerView.setOnClickListener { showControlsTemporarily() }
        binding.playerView.controllerShowTimeoutMs = if (settings.autoHideControls) 2_500 else 0

        initializePlayer()
        play(liveUrl, streamName, currentIsLive)
        if (currentIsLive) showChannelInfoBar()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        if (::settings.isInitialized) {
            subtitlesEnabled = settings.subtitlesEnabled
            applySubtitlePreference()
        }
    }

    private fun initializePlayer() {
        player = IptvPlayerFactory.create(this, subtitlesEnabled).also { exo ->
            exo.volume = 1f
            binding.playerView.player = exo
            applySubtitlePreference(exo)
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    binding.buffering.visibility =
                        if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (playbackState == Player.STATE_READY) {
                        reachedReady = true
                        compatibility.savePreferredUrl(streamId, currentUrl())
                        scheduleControlsHide()
                        playbackHandler.postDelayed({ checkAudioAndFallback() }, 1_300L)
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    ensureAudioSelection(tracks)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (tryAutomaticFallback()) return
                    binding.buffering.visibility = View.GONE
                    showControlsTemporarily()
                    Toast.makeText(
                        this@PlayerActivity,
                        "Player error: ${error.errorCodeName}. Try AUDIO FIX or EXTERNAL PLAYER.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    private fun buildStreamCandidates(primary: String): List<String> {
        if (!currentIsLive) return listOf(primary).filter { it.isNotBlank() }
        val canonicalTs = client.liveUrl(streamId)
        val canonicalHls = IptvPlayerFactory.alternateLiveUrl(canonicalTs)
        val primaryAlternate = IptvPlayerFactory.alternateLiveUrl(primary)
        return listOf(primary, primaryAlternate, canonicalTs, canonicalHls)
            .filterNotNull()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    private fun play(url: String, title: String, live: Boolean) {
        hlsFallbackAttempted = false
        reachedReady = false
        if (live) {
            streamCandidates = buildStreamCandidates(url)
            compatibility.preferredUrl(streamId)?.let { remembered ->
                if (remembered in streamCandidates) {
                    streamCandidates = listOf(remembered) + streamCandidates.filterNot { it == remembered }
                }
            }
            candidateIndex = streamCandidates.indexOf(url).takeIf { it >= 0 } ?: 0
            playInternal(streamCandidates.getOrElse(candidateIndex) { url }, title, true)
        } else {
            streamCandidates = listOf(url)
            candidateIndex = 0
            playInternal(url, title, false)
        }
    }

    private fun playInternal(url: String, title: String, live: Boolean) {
        currentTitle = title
        currentIsLive = live
        reachedReady = false
        binding.playerView.tag = url
        binding.title.text = title
        binding.liveButton.visibility = if (live) View.GONE else View.VISIBLE
        binding.buffering.visibility = View.VISIBLE
        showControlsTemporarily()

        val builder = MediaItem.Builder().setUri(url).setMediaId(url)
        when {
            url.contains(".m3u8", true) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", true) -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            url.substringBefore('?').endsWith(".ts", true) -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }

        val generation = ++playbackGeneration
        player?.apply {
            stop(); clearMediaItems(); setMediaItem(builder.build()); prepare(); playWhenReady = true
        }
        if (live) {
            playbackHandler.postDelayed({
                val exo = player ?: return@postDelayed
                if (generation != playbackGeneration || reachedReady) return@postDelayed
                if (exo.playbackState != Player.STATE_READY && !exo.isPlaying) tryNextCandidate()
            }, 9_000L)
        }
    }

    private fun checkAudioAndFallback() {
        val exo = player ?: return
        if (!currentIsLive) return
        val tracks = exo.currentTracks
        val hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO) && tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
        if (!hasAudio) {
            Toast.makeText(this, "No playable audio track detected. Trying another stream mode…", Toast.LENGTH_SHORT).show()
            tryNextCandidate()
        }
    }

    private fun tryNextCandidate(): Boolean {
        if (!currentIsLive) return false
        val next = candidateIndex + 1
        if (next >= streamCandidates.size) return false
        candidateIndex = next
        hlsFallbackAttempted = true
        playInternal(streamCandidates[next], currentTitle, true)
        Toast.makeText(this, "Trying stream mode ${next + 1}/${streamCandidates.size}", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun tryAutomaticFallback(): Boolean = tryNextCandidate()

    private fun ensureAudioSelection(tracks: Tracks) {
        val exo = player ?: return
        if (!tracks.containsType(C.TRACK_TYPE_AUDIO)) return
        exo.volume = 1f
        if (!tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        }
    }

    private fun showAudioTrackMenu() {
        val exo = player ?: return
        val tracks = exo.currentTracks
        if (!tracks.containsType(C.TRACK_TYPE_AUDIO)) {
            Toast.makeText(
                this,
                "No audio track was detected. Trying the alternate stream mode…",
                Toast.LENGTH_SHORT
            ).show()
            tryAudioFix()
            return
        }

        TrackSelectionDialogBuilder(
            this,
            "Choose audio track",
            exo,
            C.TRACK_TYPE_AUDIO
        )
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .build()
            .show()
    }

    private fun tryAudioFix() {
        player?.volume = 1f
        if (!currentIsLive) {
            player?.prepare()
            Toast.makeText(this, "Audio was reset.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tryNextCandidate()) {
            Toast.makeText(this, "All native stream modes were tried. Use VLC AUDIO for wider codec support.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleSubtitles() {
        subtitlesEnabled = !subtitlesEnabled
        settings.subtitlesEnabled = subtitlesEnabled
        applySubtitlePreference()
        binding.subtitleButton.text = if (subtitlesEnabled) "SUBTITLES ON" else "SUBTITLES OFF"
        binding.audioFixButton.text = "AUDIO TRACK"
        Toast.makeText(
            this,
            if (subtitlesEnabled) "Embedded subtitles enabled." else "Subtitles disabled.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applySubtitlePreference(exo: ExoPlayer? = player) {
        exo ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setSelectTextByDefault(subtitlesEnabled)
            .setSelectUndeterminedTextLanguage(subtitlesEnabled)
            .build()
    }

    private fun switchChannel(delta: Int) {
        if (!currentIsLive) return
        val channels = PlaybackQueueStore.channels
        if (channels.size < 2) {
            Toast.makeText(this, "No other channels are available in this list.", Toast.LENGTH_SHORT).show()
            return
        }
        val size = channels.size
        val nextIndex = (PlaybackQueueStore.currentIndex + delta + size) % size
        PlaybackQueueStore.currentIndex = nextIndex
        val channel = channels[nextIndex]
        streamId = channel.id
        streamName = channel.name
        hasCatchUp = channel.hasCatchUp
        liveUrl = settings.preferredLiveUrl(channel.url)
        compatibility.clearPreferredUrl(streamId)
        binding.catchUpButton.visibility = if (hasCatchUp) View.VISIBLE else View.GONE
        play(liveUrl, streamName, true)
        showChannelInfoBar()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    switchChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    switchChannel(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU -> {
                    if (controlsVisible) hideControls() else showControlsTemporarily()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (currentIsLive) { finish(); return true }
                    if (controlsVisible) { hideControls(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showControlsTemporarily() {
        controlsVisible = true
        binding.controls.visibility = View.VISIBLE
        binding.playerView.showController()
        scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        playbackHandler.removeCallbacks(hideControlsRunnable)
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        if (settings.autoHideControls && !inPip) {
            playbackHandler.postDelayed(hideControlsRunnable, 3_000L)
        }
    }

    private val hideControlsRunnable = Runnable { hideControls() }

    private fun hideControls() {
        controlsVisible = false
        binding.controls.visibility = View.GONE
        binding.playerView.hideController()
        enterImmersiveMode()
    }


    private fun showChannelInfoBar() {
        if (!currentIsLive) return
        binding.channelInfoBar.visibility = View.VISIBLE
        binding.infoChannelName.text = streamName
        binding.infoProgramTitle.text = "Loading program information…"
        binding.infoProgramTime.text = ""
        binding.infoProgress.progress = 0
        lifecycleScope.launch {
            runCatching { client.getEpg(streamId, 5) }.onSuccess { items ->
                val now = System.currentTimeMillis() / 1000L
                val current = items.firstOrNull { now in it.startTimestamp until it.stopTimestamp } ?: items.firstOrNull()
                if (current == null) {
                    binding.infoProgramTitle.text = "EPG unavailable"
                } else {
                    binding.infoProgramTitle.text = current.title
                    binding.infoProgramTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.startTimestamp * 1000L)) + " – " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.stopTimestamp * 1000L))
                    val duration = (current.stopTimestamp-current.startTimestamp).coerceAtLeast(1L)
                    binding.infoProgress.progress = (((now-current.startTimestamp).coerceIn(0L,duration)*100L)/duration).toInt()
                }
            }
        }
        infoBarHandler.removeCallbacksAndMessages(null)
        infoBarHandler.postDelayed({ binding.channelInfoBar.visibility = View.GONE }, 4_500L)
    }

    private fun currentUrl(): String = binding.playerView.tag as? String ?: liveUrl


    private fun openVlcAudioPlayer() {
        val urls = if (currentIsLive) buildStreamCandidates(currentUrl()) else listOf(currentUrl())
        startActivity(Intent(this, VlcAudioPlayerActivity::class.java).apply {
            putStringArrayListExtra(VlcAudioPlayerActivity.EXTRA_URLS, ArrayList(urls))
            putExtra(VlcAudioPlayerActivity.EXTRA_URL, currentUrl())
            putExtra(VlcAudioPlayerActivity.EXTRA_TITLE, currentTitle.ifBlank { streamName })
            putExtra(VlcAudioPlayerActivity.EXTRA_STREAM_ID, streamId)
        })
    }

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
            Toast.makeText(this, "Recording supports direct non-DRM transport streams.", Toast.LENGTH_LONG).show()
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

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) hideControls() else showControlsTemporarily()
    }

    override fun onDestroy() {
        playbackHandler.removeCallbacksAndMessages(null)
        infoBarHandler.removeCallbacksAndMessages(null)
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
        const val EXTRA_IS_LIVE = "is_live"
    }
}
