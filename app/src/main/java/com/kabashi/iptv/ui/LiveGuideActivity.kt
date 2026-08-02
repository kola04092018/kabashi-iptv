package com.kabashi.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.kabashi.iptv.data.*
import com.kabashi.iptv.databinding.ActivityLiveGuideBinding
import com.kabashi.iptv.player.IptvPlayerFactory
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class LiveGuideActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLiveGuideBinding
    private lateinit var client: XtreamClient
    private lateinit var favorites: FavoritesStore
    private lateinit var settings: AppSettingsStore
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private var player: ExoPlayer? = null
    private var allChannels: List<MediaEntry> = emptyList()
    private var selected: MediaEntry? = null
    private var favoritesOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveGuideBinding.inflate(layoutInflater); setContentView(binding.root)
        val credentials = SecureCredentialStore(this).load() ?: run { finish(); return }
        client = XtreamClient(credentials); favorites = FavoritesStore(this); settings = AppSettingsStore(this)
        categoryAdapter = CategoryAdapter { loadChannels(it.id) }
        channelAdapter = ChannelAdapter(::selectChannel, ::toggleFavorite) { favorites.isFavorite(it) }
        binding.categories.layoutManager = LinearLayoutManager(this); binding.categories.adapter = categoryAdapter
        binding.channels.layoutManager = LinearLayoutManager(this); binding.channels.adapter = channelAdapter
        binding.favoritesButton.setOnClickListener { favoritesOnly = !favoritesOnly; binding.favoritesButton.isSelected = favoritesOnly; showChannels() }
        player = IptvPlayerFactory.create(this, settings.subtitlesEnabled).also { binding.preview.player = it }
        loadCategories()
    }

    private fun loadCategories() { setLoading(true); lifecycleScope.launch { runCatching { client.getLiveCategories() }.onSuccess { categoryAdapter.submit(it); loadChannels("") }.onFailure(::error) } }
    private fun loadChannels(category: String) { setLoading(true); lifecycleScope.launch { runCatching { client.getLiveStreams(category) }.onSuccess { allChannels=it; showChannels(); setLoading(false); if(it.isNotEmpty()) selectChannel(it.first()) }.onFailure(::error) } }
    private fun showChannels() { val list=if(favoritesOnly) favorites.filter(ContentType.LIVE,allChannels) else allChannels; channelAdapter.submit(list); binding.channelCount.text="${list.size} channels" }

    private fun selectChannel(item: MediaEntry) {
        if (selected?.id == item.id && player?.isPlaying == true) { openFullscreen(item); return }
        selected=item
        val visible=channelAdapter.currentItems()
        PlaybackQueueStore.channels=visible.map { PlaybackChannel(it.id,it.name,client.liveUrl(it.id),it.hasCatchUp) }
        PlaybackQueueStore.currentIndex=visible.indexOfFirst { it.id==item.id }.coerceAtLeast(0)
        val url=settings.preferredLiveUrl(client.liveUrl(item.id))
        player?.apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady=true }
        binding.programTitle.text=item.name; binding.programTime.text="Loading EPG…"; binding.programDescription.text=""; binding.nextPrograms.text=""
        lifecycleScope.launch { runCatching { client.getEpg(item.id,10) }.onSuccess { showEpg(it) }.onFailure { binding.programTime.text="EPG unavailable" } }
    }

    private fun showEpg(items: List<EpgItem>) {
        val now=System.currentTimeMillis()/1000
        val current=items.firstOrNull { now in it.startTimestamp until it.stopTimestamp } ?: items.firstOrNull()
        if(current==null){ binding.programTime.text="No EPG data"; binding.programProgress.progress=0; return }
        binding.programTitle.text=current.title.ifBlank { selected?.name ?: "Live TV" }
        binding.programTime.text=time(current.startTimestamp)+" – "+time(current.stopTimestamp)
        binding.programDescription.text=current.description
        val duration=(current.stopTimestamp-current.startTimestamp).coerceAtLeast(1); binding.programProgress.progress=(((now-current.startTimestamp).coerceIn(0,duration)*100)/duration).toInt()
        binding.nextPrograms.text=items.filter { it.startTimestamp>=current.stopTimestamp }.take(4).joinToString("   •   ") { time(it.startTimestamp)+"  "+it.title }
    }
    private fun time(ts:Long)=DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts*1000))
    private fun toggleFavorite(item:MediaEntry){ val added=favorites.toggle(item); Toast.makeText(this,if(added)"Added to Favorites" else "Removed from Favorites",Toast.LENGTH_SHORT).show(); showChannels() }
    private fun openFullscreen(item:MediaEntry){ startActivity(Intent(this,PlayerActivity::class.java).apply { putExtra(PlayerActivity.EXTRA_STREAM_ID,item.id); putExtra(PlayerActivity.EXTRA_NAME,item.name); putExtra(PlayerActivity.EXTRA_DIRECT_URL,client.liveUrl(item.id)); putExtra(PlayerActivity.EXTRA_CATCH_UP,item.hasCatchUp); putExtra(PlayerActivity.EXTRA_ALLOW_RECORDING,true); putExtra(PlayerActivity.EXTRA_IS_LIVE,true) }) }
    private fun setLoading(v:Boolean){ binding.loading.visibility=if(v)View.VISIBLE else View.GONE }
    private fun error(t:Throwable){setLoading(false);Toast.makeText(this,t.message?:"Unable to load Live TV",Toast.LENGTH_LONG).show()}
    override fun onPause(){ super.onPause(); player?.pause() }
    override fun onResume(){ super.onResume(); if(selected!=null) player?.play() }
    override fun onDestroy(){ binding.preview.player=null; player?.release(); player=null; super.onDestroy() }
}
