package com.maxrave.data.control

import com.maxrave.common.Config.PLAYLIST_CLICK
import com.maxrave.data.mediaservice.RoutingMediaPlayerHandler
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlaybackSnapshot
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.repository.ControlTarget
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.refinecontrol.DEVICE_DESKTOP
import com.maxrave.refinecontrol.DEVICE_PHONE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.net.DatagramSocket
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private inline fun <reified T : Any> unimplemented(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.simpleName}.${method.name} is not part of this test")
    } as T

private class MemoryDataStore(
    private val values: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
) : DataStoreManager by unimplemented() {
    override fun getString(key: String): Flow<String?> = values.map { it[key] }

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        values.value = values.value + (key to value)
    }
}

private class FakePlayer : MediaPlayerHandler by unimplemented() {
    private val control = MutableStateFlow(ControlState(false, false, RepeatState.None, false, false, false, 1f))
    private val queue = MutableStateFlow<QueueData?>(QueueData())
    private val nowPlayingTrack = MutableStateFlow(NowPlayingTrackState.initial())
    private val index = MutableStateFlow(0)
    private var position = 0L
    private var since = TimeSource.Monotonic.markNow()
    val duration = 200_000L

    override val simpleMediaState: StateFlow<SimpleMediaState> = MutableStateFlow(SimpleMediaState.Initial)
    override val controlState: StateFlow<ControlState> = control
    override val queueData: StateFlow<QueueData?> = queue
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = nowPlayingTrack
    override val currentSongIndex: StateFlow<Int> = index
    override val nowPlaying: StateFlow<com.maxrave.domain.data.player.GenericMediaItem?> = MutableStateFlow(null)
    override val skipSegments: StateFlow<List<com.maxrave.domain.data.model.mediaService.SponsorSkipSegments>?> = MutableStateFlow(null)
    override val format: StateFlow<com.maxrave.domain.data.entities.NewFormatEntity?> = MutableStateFlow(null)

    val isPlaying get() = control.value.isPlaying
    val tracks get() = queue.value?.data?.listTracks.orEmpty()

    private fun setPlaying(playing: Boolean) {
        position = getProgress()
        since = TimeSource.Monotonic.markNow()
        control.value = control.value.copy(isPlaying = playing)
    }

    fun start(
        tracks: List<Track>,
        at: Int,
        positionMs: Long,
        play: Boolean,
        data: QueueData.Data = QueueData.Data(),
    ) {
        queue.value = QueueData(QueueData.StateSource.STATE_INITIALIZED, data.copy(listTracks = tracks))
        select(at)
        position = positionMs
        since = TimeSource.Monotonic.markNow()
        control.value = control.value.copy(isPlaying = play)
    }

    private fun select(at: Int) {
        val track = tracks[at]
        index.value = at
        nowPlayingTrack.value = NowPlayingTrackState(track.toGenericMediaItem(), track, track.toSongEntity())
        position = 0
        since = TimeSource.Monotonic.markNow()
    }

    override fun getProgress(): Long = if (isPlaying) position + since.elapsedNow().inWholeMilliseconds else position

    override fun getPlayerDuration(): Long = duration

    override fun currentOrderIndex(): Int = index.value

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            PlayerEvent.PlayPause -> setPlaying(!isPlaying)
            PlayerEvent.Next -> select(index.value + 1)
            PlayerEvent.Shuffle -> control.value = control.value.copy(isShuffle = !control.value.isShuffle)
            PlayerEvent.Repeat ->
                control.value =
                    control.value.copy(
                        repeatState =
                            when (control.value.repeatState) {
                                RepeatState.None -> RepeatState.All
                                RepeatState.All -> RepeatState.One
                                RepeatState.One -> RepeatState.None
                            },
                    )
            is PlayerEvent.UpdateProgress -> {
                position = (duration * playerEvent.newProgress / 100f).toLong()
                since = TimeSource.Monotonic.markNow()
            }
            is PlayerEvent.UpdateVolume -> control.value = control.value.copy(volume = playerEvent.newVolume)
            else -> error("unexpected $playerEvent")
        }
    }

    override fun playMediaItemInMediaSource(index: Int) {
        select(index)
        setPlaying(true)
    }

    override fun removeMediaItem(position: Int) {
        queue.value = queue.value?.removeTrackWithIndex(position)
    }

    override suspend fun addToQueue(tracks: List<Track>) {
        queue.value = queue.value?.addTrackList(tracks)
    }

    override fun reset() {
        queue.value = QueueData()
    }

    override fun setQueueData(queueData: QueueData.Data) {
        queue.value = QueueData(QueueData.StateSource.STATE_INITIALIZED, queueData)
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        select(tracks.indexOf(anyTrack as Track))
        setPlaying(true)
    }

    override suspend fun loadSnapshot(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.index !in snapshot.queue.listTracks.indices) return false
        start(snapshot.queue.listTracks, snapshot.index, snapshot.positionMs, snapshot.playWhenReady, snapshot.queue)
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RefineControlFlowTest {
    private fun track(id: String) =
        Track(
            album = null,
            artists = listOf(Artist("artist", "Artist")),
            duration = "3:20",
            durationSeconds = 200,
            isAvailable = true,
            isExplicit = false,
            likeStatus = null,
            thumbnails = null,
            title = "Song $id",
            videoId = id,
            videoType = null,
            category = null,
            feedbackTokens = null,
            resultType = null,
        )

    private class Device(
        name: String,
        type: String,
        port: Int,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val player = FakePlayer()
        val control = RefineControlRepositoryImpl(MemoryDataStore(), player, scope, type, name, discoveryPort = port)

        val screens = RoutingMediaPlayerHandler(player, control, scope)
    }

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    private suspend fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(20_000) { while (!condition()) delay(50) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    @Test
    fun controlAndTransferBetweenTwoDevices() =
        runBlocking {
            val portDesk = freePort()
            val phone = Device("Phone", DEVICE_PHONE, freePort())
            val desk = Device("Desk", DEVICE_DESKTOP, portDesk)
            try {
                phone.control.setEnabled(true)
                desk.control.setEnabled(true)
                phone.control.enabled.first { it }
                desk.control.enabled.first { it }

                withTimeout(20_000) { while (!phone.control.connectByAddress("127.0.0.1:$portDesk")) delay(200) }
                val deskId = phone.control.devices.first { it.isNotEmpty() }.single().id
                phone.control.startPairing(deskId)
                val code = phone.control.pairing.first { it != null }!!.code
                assertEquals(code, desk.control.pairing.first { it != null }!!.code)
                phone.control.confirmPairing()
                desk.control.confirmPairing()
                until("phone sees the desktop connected") { phone.control.devices.value.any { it.connected } }
                until("desktop sees the phone connected") { desk.control.devices.value.any { it.connected } }
                val phoneId = desk.control.devices.value.single().id

                val album = listOf(track("a"), track("b"), track("c"))
                desk.player.start(album, at = 0, positionMs = 30_000, play = true, data = QueueData.Data(playlistId = "PL1", playlistType = PlaylistType.ALBUM))
                until("the devices list shows what the desktop plays") { phone.control.devices.value.single().playingTitle == "Song a" }
                phone.control.controlRemote(deskId)
                assertTrue(phone.control.target.value is ControlTarget.Remote)
                until("the phone's screens show the desktop's track") {
                    phone.screens.nowPlayingState.value.track
                        ?.videoId == "a" &&
                        phone.screens.controlState.value.isPlaying
                }
                assertEquals(3, phone.screens.queueData.value?.data?.listTracks?.size)
                assertEquals("PL1", phone.screens.queueData.value?.data?.playlistId)
                assertTrue(abs(phone.screens.getProgress() - desk.player.getProgress()) < 1_500, "position is mirrored")
                assertEquals(desk.player.duration, phone.screens.getPlayerDuration())

                phone.screens.onPlayerEvent(PlayerEvent.PlayPause)
                until("pause reaches the desktop") { !desk.player.isPlaying }
                until("and comes back to the phone") { !phone.screens.controlState.value.isPlaying }
                phone.screens.onPlayerEvent(PlayerEvent.PlayPause)
                until("play reaches the desktop") { desk.player.isPlaying }
                phone.screens.onPlayerEvent(PlayerEvent.UpdateProgress(50f))
                until("the seek reaches the desktop") { abs(desk.player.getProgress() - 100_000) < 3_000 }
                phone.screens.playMediaItemInMediaSource(2)
                until("a tap in the queue plays that song there") { desk.player.currentOrderIndex() == 2 }
                until("the phone follows") { phone.screens.currentOrderIndex() == 2 }
                phone.screens.onPlayerEvent(PlayerEvent.UpdateVolume(0.4f))
                until("volume") { abs(desk.player.controlState.value.volume - 0.4f) < 0.01f }
                phone.screens.removeMediaItem(0)
                until("removing from the queue") { desk.player.tracks.map { it.videoId } == listOf("b", "c") }
                assertFalse(phone.player.isPlaying)
                assertTrue(phone.player.tracks.isEmpty())

                val picked = listOf(track("x"), track("y"))
                phone.screens.reset()
                phone.screens.setQueueData(QueueData.Data(listTracks = picked, firstPlayedTrack = picked[1], playlistId = "PL2"))
                assertEquals("PL2", phone.screens.queueData.value?.data?.playlistId)
                phone.screens.loadMediaItem(picked[1], PLAYLIST_CLICK, 1)
                until("the desktop plays the picked song") { desk.player.nowPlayingState.value.track?.videoId == "y" }
                assertEquals(listOf("x", "y"), desk.player.tracks.map { it.videoId })
                assertTrue(phone.player.tracks.isEmpty())

                desk.player.onPlayerEvent(PlayerEvent.UpdateProgress(25f))
                assertTrue(phone.control.transferHere(deskId))
                assertEquals(ControlTarget.ThisDevice, phone.control.target.value)
                assertTrue(phone.player.isPlaying)
                assertEquals("y", phone.player.nowPlayingState.value.track?.videoId)
                assertEquals(listOf("x", "y"), phone.player.tracks.map { it.videoId })
                assertEquals("PL2", phone.player.queueData.value?.data?.playlistId)
                assertTrue(phone.player.getProgress() in 50_000L..60_000L, "continues where the desktop was: ${phone.player.getProgress()}")
                until("the desktop stopped") { !desk.player.isPlaying }
                assertEquals("y", phone.screens.nowPlayingState.value.track?.videoId)
                assertTrue(phone.screens.controlState.value.isPlaying)

                phone.player.onPlayerEvent(PlayerEvent.PlayPause)
                val pausedAt = phone.player.getProgress()
                assertTrue(phone.control.transferTo(deskId))
                assertEquals(deskId, (phone.control.target.value as ControlTarget.Remote).deviceId)
                assertFalse(desk.player.isPlaying)
                assertEquals(pausedAt, desk.player.getProgress())
                assertFalse(phone.player.isPlaying)

                until("the phone shows the desktop paused") { phone.control.remote.value?.let { !it.isPlaying && it.track?.videoId == "y" } == true }
                phone.screens.onPlayerEvent(PlayerEvent.PlayPause)
                until("playing on the desktop") { desk.player.isPlaying && phone.screens.controlState.value.isPlaying }
                desk.control.setEnabled(false)
                until("the phone shows it paused and unreachable") {
                    phone.control.remote.value?.connected == false && !phone.screens.controlState.value.isPlaying
                }
                phone.screens.onPlayerEvent(PlayerEvent.PlayPause)
                until("the phone plays it itself") { phone.player.isPlaying && phone.control.target.value == ControlTarget.ThisDevice }
                assertEquals("y", phone.player.nowPlayingState.value.track?.videoId)

                desk.control.setEnabled(true)
                until("connected again") { phone.control.devices.value.any { it.connected } }
                desk.control.forget(phoneId)
                until("forgotten on both") { phone.control.devices.value.none { it.paired } && desk.control.devices.value.none { it.paired } }
                assertNull(phone.control.remote.value)
            } finally {
                phone.control.setEnabled(false)
                desk.control.setEnabled(false)
                delay(300)
                phone.scope.cancel()
                desk.scope.cancel()
            }
        }

    private suspend fun pair(
        phone: Device,
        desk: Device,
        portDesk: Int,
    ): Pair<String, String> {
        phone.control.setEnabled(true)
        desk.control.setEnabled(true)
        phone.control.enabled.first { it }
        desk.control.enabled.first { it }
        withTimeout(20_000) { while (!phone.control.connectByAddress("127.0.0.1:$portDesk")) delay(200) }
        val deskId = phone.control.devices.first { it.isNotEmpty() }.single().id
        phone.control.startPairing(deskId)
        phone.control.pairing.first { it != null }
        desk.control.pairing.first { it != null }
        phone.control.confirmPairing()
        desk.control.confirmPairing()
        until("phone sees the desktop connected") { phone.control.devices.value.any { it.connected } }
        until("desktop sees the phone connected") { desk.control.devices.value.any { it.connected } }
        return deskId to desk.control.devices.value.single().id
    }

    @Test
    fun anIdleDeviceFollowsAPlayingOneAndOneTapMovesTheMusic() =
        runBlocking {
            val portDesk = freePort()
            val phone = Device("Phone", DEVICE_PHONE, freePort())
            val desk = Device("Desk", DEVICE_DESKTOP, portDesk)
            try {
                val (deskId, _) = pair(phone, desk, portDesk)

                val album = listOf(track("a"), track("b"))
                desk.player.start(album, at = 0, positionMs = 10_000, play = true)
                until("the phone follows the desktop") {
                    (phone.control.target.value as? ControlTarget.Remote)?.deviceId == deskId && phone.control.autoAttached
                }
                until("the phone's screens show the desktop's song") { phone.screens.nowPlayingState.value.track?.videoId == "a" }
                assertEquals(ControlTarget.ThisDevice, desk.control.target.value)

                val picked = listOf(track("x"))
                phone.screens.setQueueData(QueueData.Data(listTracks = picked, firstPlayedTrack = picked[0]))
                phone.screens.loadMediaItem(picked[0], PLAYLIST_CLICK, 0)
                assertEquals(ControlTarget.ThisDevice, phone.control.target.value)
                assertTrue(phone.player.isPlaying)
                assertEquals("x", phone.player.nowPlayingState.value.track?.videoId)
                assertEquals("a", desk.player.nowPlayingState.value.track?.videoId)
                assertTrue(desk.player.isPlaying)
                delay(1_500)
                assertEquals(ControlTarget.ThisDevice, phone.control.target.value)

                assertTrue(phone.control.listenOn(deskId))
                until("the desktop plays the phone's song") { desk.player.nowPlayingState.value.track?.videoId == "x" && desk.player.isPlaying }
                assertFalse(phone.player.isPlaying)
                assertEquals(deskId, (phone.control.target.value as ControlTarget.Remote).deviceId)
                assertFalse(phone.control.autoAttached)

                assertTrue(phone.control.listenOn(null))
                assertEquals(ControlTarget.ThisDevice, phone.control.target.value)
                assertTrue(phone.player.isPlaying)
                assertEquals("x", phone.player.nowPlayingState.value.track?.videoId)
                until("the desktop stopped") { !desk.player.isPlaying }

                phone.player.onPlayerEvent(PlayerEvent.PlayPause)
                until("the phone paused") { !phone.player.isPlaying }
                desk.player.start(album, at = 1, positionMs = 0, play = true)
                until("the desktop plays b") { phone.control.devices.value.single().isPlaying }
                if (phone.control.target.value is ControlTarget.Remote) phone.control.controlThisDevice()
                assertTrue(phone.control.listenOn(deskId))
                assertEquals(deskId, (phone.control.target.value as ControlTarget.Remote).deviceId)
                assertEquals("x", phone.player.nowPlayingState.value.track?.videoId)
                assertEquals("b", desk.player.nowPlayingState.value.track?.videoId)
            } finally {
                phone.control.setEnabled(false)
                desk.control.setEnabled(false)
                delay(300)
                phone.scope.cancel()
                desk.scope.cancel()
            }
        }

    @Test
    fun onWithoutBeingSwitchedOn() =
        runBlocking<Unit> {
            val phone = Device("Phone", DEVICE_PHONE, freePort())
            try {
                withTimeout(10_000) { phone.control.enabled.first { it } }
            } finally {
                phone.control.setEnabled(false)
                delay(300)
                phone.scope.cancel()
            }
        }
}
