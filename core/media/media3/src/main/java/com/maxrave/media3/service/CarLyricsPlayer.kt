package com.maxrave.media3.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.RenditionController
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toLyrics
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "CarLyrics"
private const val TICK_MS = 200L
private const val RETRY_MS = 20_000L
private const val MAX_ATTEMPTS = 3
private const val RECHECK_WHILE_CONNECTED_MS = 30_000L
private const val CONNECTION_STATE = "CarConnectionState"
private const val CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
private val CONNECTION_URI = "content://androidx.car.app.connection".toUri()

class CarConnection(
    private val context: Context,
    private val renditions: RenditionController,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = state.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = refresh()
            },
            IntentFilter(CONNECTION_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        refresh()
        scope.launch {
            while (isActive) {
                delay(RECHECK_WHILE_CONNECTED_MS)
                if (state.value) refresh()
            }
        }
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val now =
                runCatching {
                    context.contentResolver.query(CONNECTION_URI, arrayOf(CONNECTION_STATE), null, null, null)?.use { cursor ->
                        cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(CONNECTION_STATE)) != 0
                    } ?: false
                }.getOrDefault(false)
            if (state.value == now) return@launch
            Logger.w(TAG, "car ${if (now) "connected" else "disconnected"}")
            state.value = now
            renditions.setCarConnected(now)
        }
    }
}

private class TimedLine(
    val startMs: Long,
    val text: String,
)

@UnstableApi
class CarLyricsPlayer(
    player: Player,
    private val scope: CoroutineScope,
    private val lyricsRepository: LyricsCanvasRepository,
    private val dataStoreManager: DataStoreManager,
    carConnection: CarConnection,
) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var lines: List<TimedLine> = emptyList()
    private var linesFor: String? = null
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var currentLine: String? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    init {
        scope.launch(Dispatchers.Main) {
            combine(carConnection.connected, dataStoreManager.getString(DataStoreManager.CAR_LYRICS)) { connected, setting ->
                connected && setting != DataStoreManager.Values.FALSE
            }.distinctUntilChanged().collect { on ->
                Logger.w(TAG, "car lyrics ${if (on) "on" else "off"}")
                tickJob?.cancel()
                if (on) {
                    startTicking()
                } else {
                    showLine(null)
                }
            }
        }
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata = super.getMediaMetadata().withLine()

    override fun getCurrentMediaItem(): MediaItem? =
        super.getCurrentMediaItem()?.let { item ->
            if (currentLine == null) item else item.buildUpon().setMediaMetadata(item.mediaMetadata.withLine()).build()
        }

    private fun MediaMetadata.withLine(): MediaMetadata {
        val line = currentLine ?: return this
        val song = listOfNotNull(title?.toString(), artist?.toString()).filter { it.isNotBlank() }.joinToString(" · ")
        return buildUpon()
            .setTitle(line)
            .setDisplayTitle(line)
            .setArtist(song)
            .setSubtitle(song)
            .build()
    }

    private fun startTicking() {
        tickJob =
            scope.launch(Dispatchers.Main) {
                while (isActive) {
                    runCatching { tick() }.onFailure { Logger.w(TAG, "tick failed: ${it.message}") }
                    delay(TICK_MS)
                }
            }
    }

    private fun tick() {
        val item = super.getCurrentMediaItem()
        val videoId = item?.mediaId?.takeIf { it.isNotBlank() }
        if (videoId == null) {
            showLine(null)
            return
        }
        if (videoId != linesFor) {
            linesFor = videoId
            lines = emptyList()
            attempts = 0
            nextAttemptAt = 0L
            loadJob?.cancel()
            showLine(null)
        }
        val now = System.currentTimeMillis()
        if (lines.isEmpty() && loadJob?.isActive != true && attempts < MAX_ATTEMPTS && now >= nextAttemptAt) {
            attempts++
            nextAttemptAt = now + RETRY_MS
            load(videoId, item)
        }
        val position = currentPosition
        showLine(lines.lastOrNull { it.startMs <= position }?.text?.takeIf { it.isNotBlank() && it != "♪" })
    }

    private fun load(
        videoId: String,
        item: MediaItem,
    ) {
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val durationSeconds = (duration.takeIf { it > 0 } ?: 0L).div(1000).toInt()
        loadJob =
            scope.launch(Dispatchers.IO) {
                val found = timedLyrics(videoId, title, artist, durationSeconds)
                withContext(Dispatchers.Main) {
                    if (linesFor == videoId) {
                        lines = found
                        Logger.w(TAG, "$videoId: ${found.size} timed lines (attempt $attempts)")
                    }
                }
            }
    }

    private suspend fun timedLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): List<TimedLine> {
        runCatching { lyricsRepository.getSavedLyrics(videoId).firstOrNull()?.toLyrics()?.timed() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        runCatching {
            (lyricsRepository.getYouTubeMusicLyrics(videoId).lastOrNull() as? Resource.Success)?.data?.first?.timed()
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (title.isBlank()) return emptyList()
        return runCatching {
            (lyricsRepository.getLrclibLyricsData(artist, title, durationSeconds.takeIf { it > 0 }).lastOrNull() as? Resource.Success)
                ?.data
                ?.timed()
        }.getOrNull().orEmpty()
    }

    private fun Lyrics.timed(): List<TimedLine>? {
        if (syncType != "LINE_SYNCED" && syncType != "RICH_SYNCED") return null
        return lines
            .orEmpty()
            .mapNotNull { line ->
                val start = line.startTimeMs.toLongOrNull() ?: return@mapNotNull null
                val text = line.words.replace(WORD_TIMING, "").replace(SPACES, " ").trim()
                TimedLine(start, if (CREDIT.containsMatchIn(text)) "" else text)
            }.sortedBy { it.startMs }
    }

    private fun showLine(line: String?) {
        if (line == currentLine) return
        currentLine = line
        val metadata = mediaMetadata
        listeners.forEach { listener ->
            runCatching { listener.onMediaMetadataChanged(metadata) }
                .onFailure { Logger.w(TAG, "listener failed: ${it.message}") }
        }
    }

    private companion object {
        val WORD_TIMING = Regex("""<\d+:\d+(?:\.\d+)?>""")
        val SPACES = Regex("""\s+""")
        val CREDIT =
            Regex(
                """^\s*(作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|製作人|制作|製作|监制|監製|策划|策劃|统筹|統籌|混音|母带|母帶|和声|和聲|录音|錄音|吉他|贝斯|貝斯|鼓|键盘|鍵盤|弦乐|弦樂|出品|发行|發行|OP|SP|Lyricist|Lyrics by|Composer|Composed by|Arranger|Arranged by|Producer|Produced by)[^:：]{0,12}\s*[:：]""",
                RegexOption.IGNORE_CASE,
            )
    }
}
