package com.kabashi.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kabashi.iptv.data.AccountInfo
import com.kabashi.iptv.data.ContentType
import com.kabashi.iptv.data.MediaEntry
import com.kabashi.iptv.data.SecureCredentialStore
import com.kabashi.iptv.data.XtreamClient
import com.kabashi.iptv.databinding.ActivityDashboardBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private lateinit var client: XtreamClient
    private lateinit var recentMovieAdapter: RecentMovieAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val store = SecureCredentialStore(this)
        val credentials = store.load()
        if (credentials == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        client = XtreamClient(credentials)

        recentMovieAdapter = RecentMovieAdapter(::openRecentMovie)
        binding.newMovies.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.newMovies.adapter = recentMovieAdapter
        binding.newMovies.setHasFixedSize(true)
        binding.newMovies.itemAnimator = null

        binding.liveCard.setOnClickListener { startActivity(Intent(this, LiveGuideActivity::class.java)) }
        binding.moviesCard.setOnClickListener { openSection(ContentType.VOD) }
        binding.seriesCard.setOnClickListener { openSection(ContentType.SERIES) }

        binding.homeMenu.setOnClickListener { binding.liveCard.requestFocus() }
        binding.liveMenu.setOnClickListener { startActivity(Intent(this, LiveGuideActivity::class.java)) }
        binding.moviesMenu.setOnClickListener { openSection(ContentType.VOD) }
        binding.seriesMenu.setOnClickListener { openSection(ContentType.SERIES) }
        binding.catchupMenu.setOnClickListener { startActivity(Intent(this, LiveGuideActivity::class.java)) }
        binding.favoritesMenu.setOnClickListener { openSection(ContentType.LIVE) }
        binding.multiviewMenu.setOnClickListener { openSection(ContentType.LIVE) }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.logoutButton.setOnClickListener {
            store.clear()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }

    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
    }

    private fun loadDashboard() {
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val accountRequest = async { runCatching { client.getAccountInfo() } }
            val moviesRequest = async { runCatching { client.getVodStreams() } }

            accountRequest.await()
                .onSuccess(::showAccount)
                .onFailure {
                    binding.expirationText.text = "Expiration: unavailable"
                    binding.connectionText.text = "Account status unavailable"
                }

            moviesRequest.await()
                .onSuccess { movies ->
                    val newest = movies
                        .sortedByDescending { it.addedTimestamp }
                        .take(12)
                    recentMovieAdapter.submit(newest)
                    binding.noMovies.visibility = if (newest.isEmpty()) View.VISIBLE else View.GONE
                }
                .onFailure {
                    recentMovieAdapter.submit(emptyList())
                    binding.noMovies.visibility = View.VISIBLE
                }

            binding.loading.visibility = View.GONE
        }
    }

    private fun showAccount(info: AccountInfo) {
        binding.expirationText.text = if (info.expirationTimestamp > 0L) {
            val formatted = DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(Date(info.expirationTimestamp * 1000L))
            "Expiration: $formatted"
        } else {
            "Expiration: not provided"
        }
        binding.connectionText.text = buildString {
            append("Status: ${info.status}")
            if (info.maxConnections > 0) {
                append("  •  Connections: ${info.activeConnections}/${info.maxConnections}")
            }
        }
    }

    private fun openSection(section: ContentType) {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            putExtra(HomeActivity.EXTRA_SECTION, section.name)
        })
    }

    private fun openRecentMovie(item: MediaEntry) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_ID, 0)
            putExtra(PlayerActivity.EXTRA_NAME, item.name)
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, client.vodUrl(item.id, item.extension))
            putExtra(PlayerActivity.EXTRA_CATCH_UP, false)
            putExtra(PlayerActivity.EXTRA_ALLOW_RECORDING, false)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
        })
    }
}
