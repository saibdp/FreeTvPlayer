package com.sai.freetv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        const val PLAYLIST_URL =
            "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    private lateinit var browseView: View
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private lateinit var loading: ProgressBar

    private var player: ExoPlayer? = null
    private var groups: List<Group> = emptyList()
    private var playingList: List<Channel> = emptyList()
    private var playingIndex = -1
    private var retriedAsHls = false

    private val groupAdapter = GroupAdapter(onSelected = ::showGroup)
    private val channelAdapter = ChannelAdapter(onClick = ::onChannelClicked)
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private val isPlaying get() = playerView.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        browseView = findViewById(R.id.browse)
        groupList = findViewById(R.id.groupList)
        channelList = findViewById(R.id.channelList)
        statusText = findViewById(R.id.status)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        loading = findViewById(R.id.loading)

        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)

        setUpList(groupList, groupAdapter)
        setUpList(channelList, channelAdapter)

        loadPlaylist()
    }

    private fun setUpList(list: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // Change animations swap the row view and would steal D-pad focus
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    // ---------------------------------------------------------------- playlist

    private fun loadPlaylist() {
        loading.visibility = View.VISIBLE
        statusText.text = getString(R.string.loading)
        lifecycleScope.launch {
            val (channels, fromCache) = withContext(Dispatchers.IO) { fetchChannels() }
            loading.visibility = View.GONE
            if (channels.isEmpty()) {
                statusText.text = getString(R.string.load_failed)
                return@launch
            }
            val byGroup = channels.groupBy { it.group } // keeps playlist order
            groups = listOf(Group(getString(R.string.all_channels), channels)) +
                byGroup.map { (name, list) -> Group(name, list) }
            groupAdapter.items = groups
            showGroup(0)
            statusText.text = getString(
                if (fromCache) R.string.status_cached else R.string.status_live,
                channels.size, byGroup.size,
            )
            groupList.post { groupList.getChildAt(0)?.requestFocus() }
        }
    }

    /** Downloads the playlist; falls back to the last good copy if offline. */
    private fun fetchChannels(): Pair<List<Channel>, Boolean> {
        val cache = File(filesDir, "playlist.m3u8")
        try {
            val conn = URL(PLAYLIST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val parsed = M3uParser.parse(text)
            if (parsed.isNotEmpty()) {
                cache.writeText(text)
                return parsed to false
            }
        } catch (_: Exception) {
            // fall through to cache
        }
        return if (cache.exists()) M3uParser.parse(cache.readText()) to true
        else emptyList<Channel>() to false
    }

    private fun showGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        channelAdapter.items = group.channels
        channelList.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- playback

    private fun onChannelClicked(position: Int) {
        playingList = channelAdapter.items
        play(position)
    }

    private fun play(index: Int) {
        val ch = playingList.getOrNull(index) ?: return
        if (ch.isExternal) {
            openExternally(ch)
            return
        }
        playingIndex = index
        retriedAsHls = false
        val p = player ?: buildPlayer().also {
            player = it
            playerView.player = it
        }
        p.setMediaItem(mediaItemFor(ch, forceHls = false))
        p.prepare()
        p.playWhenReady = true

        browseView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        playerView.requestFocus()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverlay("${index + 1}  ${ch.name}\n${ch.group}")
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun mediaItemFor(ch: Channel, forceHls: Boolean): MediaItem {
        val path = Uri.parse(ch.url).path.orEmpty().lowercase()
        val mime = when {
            forceHls || path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null // let ExoPlayer detect (.ts, .mp4, rtsp://, ...)
        }
        return MediaItem.Builder().setUri(ch.url).apply { mime?.let { setMimeType(it) } }.build()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val ch = playingList.getOrNull(playingIndex) ?: return
            when {
                // Live stream fell too far behind: jump back to the live edge
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                // Many IPTV URLs are HLS without a .m3u8 extension: try once as HLS
                !retriedAsHls && p.currentMediaItem?.localConfiguration?.mimeType == null -> {
                    retriedAsHls = true
                    p.setMediaItem(mediaItemFor(ch, forceHls = true))
                    p.prepare()
                }
                else -> showOverlay(
                    getString(R.string.cannot_play, ch.name, error.errorCodeName),
                    sticky = true,
                )
            }
        }
    }

    /** Channel up / down while watching; skips YouTube/Twitch entries. */
    private fun zap(step: Int) {
        if (playingList.isEmpty()) return
        var i = playingIndex
        repeat(playingList.size) {
            i = (i + step + playingList.size) % playingList.size
            if (!playingList[i].isExternal) {
                play(i)
                return
            }
        }
    }

    private fun stopPlayback() {
        player?.stop()
        playerView.visibility = View.GONE
        overlay.visibility = View.GONE
        browseView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        focusChannelRow()
    }

    private fun focusChannelRow() {
        if (playingList !== channelAdapter.items) return
        val i = playingIndex.coerceAtLeast(0)
        channelList.scrollToPosition(i)
        channelList.post {
            channelList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
        }
    }

    private fun openExternally(ch: Channel) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ch.url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for, ch.name), Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlay(text: String, sticky: Boolean = false) {
        overlay.removeCallbacks(hideOverlay)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        if (!sticky) overlay.postDelayed(hideOverlay, 4_000)
    }

    // ---------------------------------------------------------------- remote keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (isPlaying) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) stopPlayback()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (down) zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (down) zap(+1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (down && event.repeatCount == 0) {
                        player?.let { it.playWhenReady = !it.playWhenReady }
                        val ch = playingList.getOrNull(playingIndex)
                        val state = if (player?.playWhenReady == true) "▶" else "❚❚"
                        showOverlay("$state  ${playingIndex + 1}  ${ch?.name.orEmpty()}")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        } else if (down && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            // From the channel column, always go back to the *selected* group,
            // not whichever group row happens to be beside the focused channel.
            val focused = currentFocus
            if (focused != null && channelList.findContainingItemView(focused) != null) {
                groupList.findViewHolderForAdapterPosition(groupAdapter.selected)
                    ?.itemView?.requestFocus()
                    ?: groupList.scrollToPosition(groupAdapter.selected)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (isPlaying) stopPlayback()
        player?.release()
        player = null
        playerView.player = null
    }
}
