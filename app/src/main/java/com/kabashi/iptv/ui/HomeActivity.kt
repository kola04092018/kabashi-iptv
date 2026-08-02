package com.kabashi.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kabashi.iptv.data.AppSettingsStore
import com.kabashi.iptv.data.ContentType
import com.kabashi.iptv.data.MediaEntry
import com.kabashi.iptv.data.PlaybackChannel
import com.kabashi.iptv.data.PlaybackQueueStore
import com.kabashi.iptv.data.SecureCredentialStore
import com.kabashi.iptv.data.SeriesEpisode
import com.kabashi.iptv.data.XtreamClient
import com.kabashi.iptv.data.FavoritesStore
import com.kabashi.iptv.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var client: XtreamClient
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var settings: AppSettingsStore
    private lateinit var favorites: FavoritesStore
    private var favoritesOnly = false
    private var loadedItems: List<MediaEntry> = emptyList()
    private var currentSection = ContentType.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val credentials = SecureCredentialStore(this).load()
        if (credentials == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        client = XtreamClient(credentials)
        settings = AppSettingsStore(this)
        favorites = FavoritesStore(this)

        currentSection = runCatching {
            ContentType.valueOf(intent.getStringExtra(EXTRA_SECTION).orEmpty())
        }.getOrDefault(ContentType.LIVE)

        categoryAdapter = CategoryAdapter { category -> loadItems(category.id) }
        channelAdapter = ChannelAdapter(::openItem, ::toggleFavorite) { favorites.isFavorite(it) }

        binding.categories.layoutManager = LinearLayoutManager(this)
        binding.categories.adapter = categoryAdapter
        binding.categories.setHasFixedSize(true)
        binding.categories.itemAnimator = null

        binding.channels.layoutManager = LinearLayoutManager(this)
        binding.channels.adapter = channelAdapter
        binding.channels.setHasFixedSize(true)
        binding.channels.itemAnimator = null

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearch(binding.searchInput.text?.toString().orEmpty())
                true
            } else false
        }
        binding.searchButton.setOnClickListener {
            applySearch(binding.searchInput.text?.toString().orEmpty())
        }
        binding.liveTvButton.setOnClickListener { switchSection(ContentType.LIVE) }
        binding.moviesButton.setOnClickListener { switchSection(ContentType.VOD) }
        binding.seriesButton.setOnClickListener { switchSection(ContentType.SERIES) }
        binding.multiViewButton.setOnClickListener { showMultiViewPicker() }
        binding.favoritesButton.setOnClickListener { favoritesOnly = !favoritesOnly; applySearch(binding.searchInput.text?.toString().orEmpty()) }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.homeButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }

        applyCompactInterface()
        switchSection(currentSection)
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) applyCompactInterface()
    }

    private fun applyCompactInterface() {
        val widthDp = if (settings.compactInterface) 210 else 250
        binding.categories.layoutParams = binding.categories.layoutParams.apply {
            width = (widthDp * resources.displayMetrics.density).toInt()
        }
    }

    private fun switchSection(section: ContentType) {
        currentSection = section
        loadedItems = emptyList()
        channelAdapter.submit(emptyList())
        binding.searchInput.setText("")
        binding.liveTvButton.isSelected = section == ContentType.LIVE
        binding.moviesButton.isSelected = section == ContentType.VOD
        binding.seriesButton.isSelected = section == ContentType.SERIES
        binding.liveTvButton.alpha = if (section == ContentType.LIVE) 1f else 0.66f
        binding.moviesButton.alpha = if (section == ContentType.VOD) 1f else 0.66f
        binding.seriesButton.alpha = if (section == ContentType.SERIES) 1f else 0.66f
        binding.multiViewButton.visibility = if (section == ContentType.LIVE) View.VISIBLE else View.GONE
        binding.searchInput.hint = when (section) {
            ContentType.LIVE -> "Search live channels"
            ContentType.VOD -> "Search movies"
            ContentType.SERIES -> "Search series"
        }
        binding.channelCount.text = when (section) {
            ContentType.LIVE -> "Live TV"
            ContentType.VOD -> "Movies"
            ContentType.SERIES -> "Series"
        }
        loadCategories()
    }

    private fun loadCategories() {
        setLoading(true)
        lifecycleScope.launch {
            val result = runCatching {
                when (currentSection) {
                    ContentType.LIVE -> client.getLiveCategories()
                    ContentType.VOD -> client.getVodCategories()
                    ContentType.SERIES -> client.getSeriesCategories()
                }
            }
            result.onSuccess {
                categoryAdapter.submit(it)
                loadItems("")
            }.onFailure { showError(it) }
        }
    }

    private fun loadItems(categoryId: String) {
        setLoading(true)
        lifecycleScope.launch {
            val sectionAtStart = currentSection
            val result = runCatching {
                when (sectionAtStart) {
                    ContentType.LIVE -> client.getLiveStreams(categoryId)
                    ContentType.VOD -> client.getVodStreams(categoryId)
                    ContentType.SERIES -> client.getSeries(categoryId)
                }
            }
            if (sectionAtStart != currentSection) return@launch
            result.onSuccess {
                loadedItems = if (sectionAtStart == ContentType.VOD) {
                    it.sortedByDescending { movie -> movie.addedTimestamp }
                } else it
                channelAdapter.submit(loadedItems)
                updateCount(loadedItems.size)
                setLoading(false)
                binding.channels.scrollToPosition(0)
            }.onFailure { showError(it) }
        }
    }

    private fun updateCount(count: Int) {
        binding.channelCount.text = when (currentSection) {
            ContentType.LIVE -> "$count channels"
            ContentType.VOD -> "$count movies"
            ContentType.SERIES -> "$count series"
        }
    }

    private fun applySearch(query: String) {
        val normalized = query.trim()
        val base = if (favoritesOnly) favorites.filter(currentSection, loadedItems) else loadedItems
        val result = if (normalized.isBlank()) base else base.filter { it.name.contains(normalized, ignoreCase = true) }
        channelAdapter.submit(result)
        updateCount(result.size)
    }

    private fun toggleFavorite(item: MediaEntry) {
        val added = favorites.toggle(item)
        Toast.makeText(this, if (added) "Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        applySearch(binding.searchInput.text?.toString().orEmpty())
    }

    private fun openItem(item: MediaEntry) {
        when (item.type) {
            ContentType.LIVE -> {
                val visibleChannels = channelAdapter.currentItems().filter { it.type == ContentType.LIVE }
                PlaybackQueueStore.channels = visibleChannels.map {
                    PlaybackChannel(
                        id = it.id,
                        name = it.name,
                        url = client.liveUrl(it.id),
                        hasCatchUp = it.hasCatchUp
                    )
                }
                PlaybackQueueStore.currentIndex = visibleChannels.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                openPlayer(
                    name = item.name,
                    url = client.liveUrl(item.id),
                    streamId = item.id,
                    hasCatchUp = item.hasCatchUp,
                    allowRecording = true,
                    isLive = true
                )
            }
            ContentType.VOD -> openPlayer(
                name = item.name,
                url = client.vodUrl(item.id, item.extension),
                streamId = 0,
                hasCatchUp = false,
                allowRecording = false,
                isLive = false
            )
            ContentType.SERIES -> loadSeriesEpisodes(item)
        }
    }

    private fun loadSeriesEpisodes(series: MediaEntry) {
        setLoading(true)
        lifecycleScope.launch {
            runCatching { client.getSeriesEpisodes(series.id) }
                .onSuccess { episodes ->
                    setLoading(false)
                    if (episodes.isEmpty()) {
                        Toast.makeText(this@HomeActivity, "No episodes were returned by the provider.", Toast.LENGTH_LONG).show()
                    } else {
                        showEpisodePicker(series, episodes)
                    }
                }
                .onFailure { showError(it) }
        }
    }

    private fun showEpisodePicker(series: MediaEntry, episodes: List<SeriesEpisode>) {
        val labels = episodes.map { episode ->
            val season = episode.season.coerceAtLeast(0).toString().padStart(2, '0')
            val number = episode.episodeNumber.coerceAtLeast(0).toString().padStart(2, '0')
            "S${season}E${number}  •  ${episode.title}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(series.name)
            .setItems(labels) { _, which ->
                val episode = episodes[which]
                openPlayer(
                    name = "${series.name} — ${episode.title}",
                    url = client.seriesUrl(episode.id, episode.extension),
                    streamId = 0,
                    hasCatchUp = false,
                    allowRecording = false,
                    isLive = false
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openPlayer(
        name: String,
        url: String,
        streamId: Int,
        hasCatchUp: Boolean,
        allowRecording: Boolean,
        isLive: Boolean
    ) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_ID, streamId)
            putExtra(PlayerActivity.EXTRA_NAME, name)
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, url)
            putExtra(PlayerActivity.EXTRA_CATCH_UP, hasCatchUp)
            putExtra(PlayerActivity.EXTRA_ALLOW_RECORDING, allowRecording)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, isLive)
        })
    }

    private fun showMultiViewPicker() {
        if (currentSection != ContentType.LIVE) return
        val channels = channelAdapter.currentItems()
        if (channels.isEmpty()) {
            Toast.makeText(this, "No channels are currently visible.", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(channels.size)
        val selected = linkedSetOf<Int>()
        val dialog = AlertDialog.Builder(this)
            .setTitle("MULTIVIEW — Select 1 to 4 channels")
            .setMultiChoiceItems(channels.map { it.name }.toTypedArray(), checked) { dialogInterface, which, isChecked ->
                if (isChecked && selected.size >= 4) {
                    checked[which] = false
                    (dialogInterface as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, "MultiView supports up to 4 channels.", Toast.LENGTH_SHORT).show()
                } else if (isChecked) {
                    selected.add(which)
                } else {
                    selected.remove(which)
                }
            }
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("OPEN MULTIVIEW") { _, _ ->
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Select at least one channel.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val chosen = selected.map { channels[it] }
                startActivity(Intent(this, MultiViewActivity::class.java).apply {
                    putStringArrayListExtra(
                        MultiViewActivity.EXTRA_TITLES,
                        ArrayList(chosen.map { it.name })
                    )
                    putStringArrayListExtra(
                        MultiViewActivity.EXTRA_URLS,
                        ArrayList(chosen.map { client.liveUrl(it.id) })
                    )
                })
            }
            .create()
        dialog.show()
    }

    private fun setLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(error: Throwable) {
        setLoading(false)
        Toast.makeText(this, error.message ?: "Unable to load provider data.", Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_SECTION = "content_section"
    }
}
