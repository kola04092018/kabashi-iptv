package com.kabashi.iptv.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kabashi.iptv.R
import com.kabashi.iptv.data.AppSettingsStore
import com.kabashi.iptv.data.LiveStreamMode
import com.kabashi.iptv.data.PlaybackEngine
import com.kabashi.iptv.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsStore(this)

        when (settings.liveStreamMode) {
            LiveStreamMode.AUTO -> binding.streamModeGroup.check(R.id.streamAuto)
            LiveStreamMode.MPEG_TS -> binding.streamModeGroup.check(R.id.streamTs)
            LiveStreamMode.HLS -> binding.streamModeGroup.check(R.id.streamHls)
        }
        when (settings.playbackEngine) {
            PlaybackEngine.INTERNAL -> binding.playerInternal.check()
            PlaybackEngine.VLC -> binding.playerVlc.check()
            PlaybackEngine.EXTERNAL -> binding.playerExternal.check()
        }
        binding.subtitleSwitch.isChecked = settings.subtitlesEnabled
        binding.autoHideSwitch.isChecked = settings.autoHideControls
        binding.compactSwitch.isChecked = settings.compactInterface

        binding.saveButton.setOnClickListener {
            settings.liveStreamMode = when (binding.streamModeGroup.checkedRadioButtonId) {
                R.id.streamTs -> LiveStreamMode.MPEG_TS
                R.id.streamHls -> LiveStreamMode.HLS
                else -> LiveStreamMode.AUTO
            }
            settings.playbackEngine = when (binding.playerModeGroup.checkedRadioButtonId) {
                R.id.playerVlc -> PlaybackEngine.VLC
                R.id.playerExternal -> PlaybackEngine.EXTERNAL
                else -> PlaybackEngine.INTERNAL
            }
            settings.subtitlesEnabled = binding.subtitleSwitch.isChecked
            settings.autoHideControls = binding.autoHideSwitch.isChecked
            settings.compactInterface = binding.compactSwitch.isChecked
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.cancelButton.setOnClickListener { finish() }
    }
}
