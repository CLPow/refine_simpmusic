package com.maxrave.data.control

import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.data.security.SecretCodec
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaybackSnapshot
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.repository.ControlDevice
import com.maxrave.domain.repository.ControlDeviceType
import com.maxrave.domain.repository.ControlNotice
import com.maxrave.domain.repository.ControlPairingPrompt
import com.maxrave.domain.repository.ControlTarget
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.domain.repository.RemoteCommand
import com.maxrave.domain.repository.RemotePlayback
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.refinecontrol.Command
import com.maxrave.refinecontrol.ControlEngine
import com.maxrave.refinecontrol.DEFAULT_DISCOVERY_PORT
import com.maxrave.refinecontrol.DEVICE_PHONE
import com.maxrave.refinecontrol.DeviceInfo
import com.maxrave.refinecontrol.LocalPlayerPort
import com.maxrave.refinecontrol.PairStore
import com.maxrave.refinecontrol.PairedPeer
import com.maxrave.refinecontrol.PlayerState
import com.maxrave.refinecontrol.QueueState
import com.maxrave.refinecontrol.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

private const val TAG = "RefineControl"
private const val KEY_ENABLED = "refine_control_enabled"
private const val KEY_DEVICE_ID = "refine_control_device_id"
private const val KEY_DEVICE_NAME = "refine_control_device_name"
private const val KEY_PEERS = "refine_control_peers"
private const val SECRET_PURPOSE = "refine_control"
private const val ON = "TRUE"
private const val OFF = "FALSE"

private const val SEEK_DRIFT_MS = 1_500L

private const val POSITION_REFRESH_MS = 5_000L

private const val TAKEOVER_TIMEOUT_MS = 20_000L

internal interface SystemVolume {
    fun get(): Float

    fun set(volume: Float)
}

internal class RefineControlRepositoryImpl(
    private val dataStore: DataStoreManager,
    private val local: MediaPlayerHandler,
    private val scope: CoroutineScope,
    private val deviceType: String,
    private val defaultDeviceName: String,
    private val systemVolume: SystemVolume? = null,
    private val onScanning: (Boolean) -> Unit = {},
    private val discoveryPort: Int = System.getProperty("refine.control.port")?.toIntOrNull() ?: DEFAULT_DISCOVERY_PORT,
) : RefineControlRepository,
    LocalPlayerPort,
    PairStore {
    override val isSupported: Boolean = true

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val trackListSerializer = ListSerializer(Track.serializer())
    private val peerListSerializer = ListSerializer(PairedPeer.serializer())
    private val playerContext = scope.coroutineContext.minusKey(Job)

    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _deviceName = MutableStateFlow(defaultDeviceName)
    override val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _devices = MutableStateFlow<List<ControlDevice>>(emptyList())
    override val devices: StateFlow<List<ControlDevice>> = _devices.asStateFlow()

    private val _target = MutableStateFlow<ControlTarget>(ControlTarget.ThisDevice)
    override val target: StateFlow<ControlTarget> = _target.asStateFlow()

    private val _remote = MutableStateFlow<RemotePlayback?>(null)
    override val remote: StateFlow<RemotePlayback?> = _remote.asStateFlow()

    private val _pairing = MutableStateFlow<ControlPairingPrompt?>(null)
    override val pairing: StateFlow<ControlPairingPrompt?> = _pairing.asStateFlow()

    private val _notices = MutableSharedFlow<ControlNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val notices: SharedFlow<ControlNotice> = _notices.asSharedFlow()

    @Volatile
    private var engine: ControlEngine? = null

    @Volatile
    private var peers: List<PairedPeer> = emptyList()

    private val lifecycle = Mutex()
    private val publishNow = Channel<Unit>(Channel.CONFLATED)
    private var publishJob: Job? = null
    private var queueRev = 0L
    private var publishedQueue: QueueData.Data? = null

    private var decodedQueue: Pair<QueueState, QueueData.Data>? = null
    private var decodedTrack: Pair<String, Track>? = null
    private var encodedTrack: Pair<Track, String>? = null

    @Volatile
    private var autoDeviceId: String? = null

    private val declined = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override val autoAttached: Boolean
        get() = autoDeviceId != null && (_target.value as? ControlTarget.Remote)?.deviceId == autoDeviceId

    init {
        scope.launch {
            val id =
                dataStore.getString(KEY_DEVICE_ID).first()?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString().also { dataStore.putString(KEY_DEVICE_ID, it) }
            val name = dataStore.getString(KEY_DEVICE_NAME).first()?.takeIf { it.isNotBlank() } ?: defaultDeviceName
            _deviceName.value = name
            peers = withContext(Dispatchers.IO) { loadPeers() }
            val created =
                ControlEngine(
                    scope = scope,
                    self = DeviceInfo(id, name, deviceType),
                    store = this@RefineControlRepositoryImpl,
                    port = this@RefineControlRepositoryImpl,
                    discoveryPort = discoveryPort,
                    log = { Logger.w(TAG, it) },
                )
            engine = created
            launch { combine(created.devices, created.players) { devices, players -> devices to players }.collect { (d, p) -> onPeers(d, p) } }
            launch { watchPairing(created) }
            if (dataStore.getString(KEY_ENABLED).first() != OFF) switchOn()
            launch {
                local.controlState.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                    if (playing && autoAttached) controlThisDevice()
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        scope.launch {
            dataStore.putString(KEY_ENABLED, if (enabled) ON else OFF)
            if (enabled) switchOn() else switchOff()
        }
    }

    private fun switchOn() {
        val engine = engine ?: return
        if (_enabled.value) return
        _enabled.value = true
        scope.launch(Dispatchers.IO) { lifecycle.withLock { engine.start() } }
        publishJob = scope.launch { publishLoop(engine) }
    }

    private fun switchOff() {
        if (!_enabled.value) return
        _enabled.value = false
        publishJob?.cancel()
        publishJob = null
        publishedQueue = null
        _target.value = ControlTarget.ThisDevice
        _remote.value = null
        onScanning(false)
        val running = engine ?: return
        scope.launch(Dispatchers.IO) { lifecycle.withLock { running.stop() } }
    }

    override fun setDeviceName(name: String) {
        val clean = name.trim().take(40).ifBlank { defaultDeviceName }
        _deviceName.value = clean
        engine?.rename(clean)
        scope.launch { dataStore.putString(KEY_DEVICE_NAME, clean) }
    }

    override fun setScanning(scanning: Boolean) {
        if (!_enabled.value) return
        onScanning(scanning)
        if (scanning) engine?.announceNow()
    }

    private suspend fun loadPeers(): List<PairedPeer> =
        runCatching {
            val stored = dataStore.getString(KEY_PEERS).first().orEmpty()
            if (stored.isEmpty()) emptyList() else json.decodeFromString(peerListSerializer, SecretCodec.decode(stored, SECRET_PURPOSE))
        }.getOrDefault(emptyList())

    override fun load(): List<PairedPeer> = peers

    override fun save(peers: List<PairedPeer>) {
        this.peers = peers
        scope.launch(Dispatchers.IO) {
            val encoded = if (peers.isEmpty()) "" else SecretCodec.encode(json.encodeToString(peerListSerializer, peers), SECRET_PURPOSE)
            if (peers.isEmpty() || encoded.isNotEmpty()) dataStore.putString(KEY_PEERS, encoded)
        }
    }

    override fun startPairing(deviceId: String) {
        engine?.startPairing(deviceId)
    }

    override fun confirmPairing() {
        engine?.confirmPairing()
    }

    override fun cancelPairing() {
        engine?.cancelPairing()
    }

    override fun forget(deviceId: String) {
        if ((_target.value as? ControlTarget.Remote)?.deviceId == deviceId) controlThisDevice()
        engine?.forget(deviceId)
    }

    private suspend fun watchPairing(engine: ControlEngine) {
        var last: com.maxrave.refinecontrol.PairingPrompt? = null
        engine.pairing.collect { prompt ->
            _pairing.value = prompt?.let { ControlPairingPrompt(it.deviceName, it.code, waitingForOther = it.confirmedHere) }
            val finished = last
            if (prompt == null && finished != null && peers.any { it.id == finished.deviceId }) {
                _notices.tryEmit(ControlNotice.Paired(finished.deviceName))
            }
            last = prompt
        }
    }

    override suspend fun connectByAddress(address: String): Boolean = engine?.connectByAddress(address) ?: false

    private fun onPeers(
        peerDevices: List<com.maxrave.refinecontrol.PeerDevice>,
        players: Map<String, RemotePlayer>,
    ) {
        _devices.value =
            peerDevices.map { device ->
                val state = players[device.id]?.state?.takeIf { device.connected && it.trackId != null }
                ControlDevice(
                    id = device.id,
                    name = device.name,
                    type = if (device.type == DEVICE_PHONE) ControlDeviceType.PHONE else ControlDeviceType.DESKTOP,
                    paired = device.paired,
                    connected = device.connected,
                    playingTitle = state?.title?.takeIf { it.isNotBlank() },
                    isPlaying = state?.isPlaying == true,
                )
            }
        _devices.value.forEach { if (!it.isPlaying || !it.connected) declined.remove(it.id) }
        val current = _target.value as? ControlTarget.Remote ?: return maybeAutoAttach()
        val device = peerDevices.firstOrNull { it.id == current.deviceId }
        if (autoAttached && device?.connected != true) {
            autoDeviceId = null
            _target.value = ControlTarget.ThisDevice
            _remote.value = null
            return
        }
        if (device == null || !device.paired) {
            controlThisDevice()
            return
        }
        if (device.name != current.deviceName) _target.value = current.copy(deviceName = device.name)
        val wasConnected = _remote.value?.connected
        val mirrored = players[device.id]?.let { toPlayback(device.id, device.name, device.connected, it) }
        if (mirrored != null) _remote.value = mirrored else _remote.update { it?.copy(connected = device.connected, deviceName = device.name) }
        if (wasConnected == true && !device.connected) _notices.tryEmit(ControlNotice.Disconnected(device.name))
    }

    private fun toPlayback(
        deviceId: String,
        deviceName: String,
        connected: Boolean,
        player: RemotePlayer,
    ): RemotePlayback {
        val state = player.state
        val queue = player.queue?.let { decodeQueue(it) } ?: QueueData.Data()
        val track =
            state.trackJson?.let { decodeTrack(it) }
                ?: queue.listTracks.getOrNull(state.index)?.takeIf { it.videoId == state.trackId }
        return RemotePlayback(
            deviceId = deviceId,
            deviceName = deviceName,
            connected = connected,
            track = track,
            queue = queue,
            index = state.index,
            positionMs = state.positionMs,
            positionAt = TimeSource.Monotonic.markNow() - (System.nanoTime() - player.receivedAtNanos).nanoseconds,
            durationMs = state.durationMs,
            isPlaying = state.isPlaying && connected,
            loading = state.loading && connected,
            shuffle = state.shuffle,
            repeat =
                when (state.repeat) {
                    1 -> RepeatState.One
                    2 -> RepeatState.All
                    else -> RepeatState.None
                },
            liked = state.liked,
            volume = state.volume,
            nextAvailable = state.nextAvailable,
            previousAvailable = state.prevAvailable,
        )
    }

    private fun decodeQueue(queue: QueueState): QueueData.Data {
        decodedQueue?.let { if (it.first == queue) return it.second }
        val data =
            QueueData.Data(
                listTracks = runCatching { json.decodeFromString(trackListSerializer, queue.tracksJson) }.getOrDefault(emptyList()),
                firstPlayedTrack = null,
                playlistId = queue.playlistId,
                playlistName = queue.playlistName,
                playlistType = queue.playlistType?.let { runCatching { PlaylistType.valueOf(it) }.getOrNull() },
                continuation = queue.continuation,
                autoplayStartIndex = queue.autoplayStartIndex,
            )
        decodedQueue = queue to data
        return data
    }

    private fun decodeTrack(text: String): Track? {
        decodedTrack?.let { if (it.first == text) return it.second }
        return runCatching { json.decodeFromString(Track.serializer(), text) }.getOrNull()?.also { decodedTrack = text to it }
    }

    private fun encodeTrack(track: Track): String {
        encodedTrack?.let { if (it.first == track) return it.second }
        return json.encodeToString(Track.serializer(), track).also { encodedTrack = track to it }
    }

    private fun QueueData.Data.toQueueState(rev: Long): QueueState {
        val local = playlistId?.startsWith(LOCAL_PLAYLIST_ID) == true || playlistType == PlaylistType.LOCAL_PLAYLIST
        return QueueState(
            rev = rev,
            tracksJson = json.encodeToString(trackListSerializer, listTracks),
            playlistId = if (local) null else playlistId,
            playlistName = playlistName,
            playlistType = (if (local) PlaylistType.PLAYLIST else playlistType)?.name,
            continuation = if (local) null else continuation,
            autoplayStartIndex = autoplayStartIndex,
        )
    }

    private suspend fun publishLoop(engine: ControlEngine): Unit =
        coroutineScope {
        launch {
            merge(
                local.nowPlayingState.map { },
                local.controlState.map { },
                local.currentSongIndex.map { },
                local.queueData.map { },
                local.simpleMediaState.map { it is SimpleMediaState.Buffering }.distinctUntilChanged().map { },
            ).collect { publishNow.trySend(Unit) }
        }
        var last: PlayerState? = null
        var lastAt = TimeSource.Monotonic.markNow()
        while (true) {
            withTimeoutOrNull(1_000L) { publishNow.receive() }
            delay(60)
            publishNow.tryReceive()
            val queue = local.queueData.value?.data ?: QueueData.Data()
            if (queue != publishedQueue) {
                publishedQueue = queue
                queueRev++
                engine.publishQueue(queue.toQueueState(queueRev))
            }
            val state = currentState()
            val before = last
            val elapsed = lastAt.elapsedNow().inWholeMilliseconds
            val expected = before?.let { if (it.isPlaying && !it.loading) it.positionMs + elapsed else it.positionMs }
            val changed =
                before == null ||
                    state.copy(positionMs = 0) != before.copy(positionMs = 0) ||
                    abs(state.positionMs - (expected ?: 0L)) > SEEK_DRIFT_MS ||
                    (state.isPlaying && elapsed >= POSITION_REFRESH_MS)
            if (changed) {
                engine.publishState(state)
                last = state
                lastAt = TimeSource.Monotonic.markNow()
            }
        }
        }

    private fun currentState(): PlayerState {
        val nowPlaying = local.nowPlayingState.value
        val control = local.controlState.value
        val track = nowPlaying.track ?: nowPlaying.songEntity?.toTrack()
        val durationMs =
            local.getPlayerDuration().takeIf { it > 0L }
                ?: ((nowPlaying.songEntity?.durationSeconds ?: track?.durationSeconds ?: 0) * 1000L)
        return PlayerState(
            trackId = track?.videoId,
            trackJson = track?.let { encodeTrack(it) },
            title = track?.title.orEmpty(),
            artist = track?.artists?.joinToString(", ") { it.name }.orEmpty(),
            durationMs = durationMs,
            index = if (track == null) -1 else local.currentOrderIndex(),
            queueRev = queueRev,
            positionMs = if (track == null) 0L else local.getProgress().coerceAtLeast(0L),
            isPlaying = control.isPlaying,
            loading = local.simpleMediaState.value is SimpleMediaState.Buffering,
            shuffle = control.isShuffle,
            repeat =
                when (control.repeatState) {
                    RepeatState.One -> 1
                    RepeatState.All -> 2
                    RepeatState.None -> 0
                },
            liked = control.isLiked,
            volume = systemVolume?.get() ?: control.volume,
            nextAvailable = control.isNextAvailable,
            prevAvailable = control.isPreviousAvailable,
        )
    }

    override suspend fun onCommand(
        from: DeviceInfo,
        command: Command,
    ): String? =
        withContext(playerContext) {
            if (!_enabled.value) return@withContext "off"
            val control = local.controlState.value
            when (command) {
                Command.Play -> {
                    if (!local.nowPlayingState.value.isNotEmpty()) {
                        if (!local.restoreQueueAndPlay()) return@withContext "nothing_to_play"
                    } else if (!control.isPlaying) {
                        local.onPlayerEvent(PlayerEvent.PlayPause)
                    }
                }

                Command.Pause -> {
                    if (control.isPlaying) local.onPlayerEvent(PlayerEvent.PlayPause)
                }

                Command.Next -> {
                    local.onPlayerEvent(PlayerEvent.Next)
                }

                Command.Previous -> {
                    local.onPlayerEvent(PlayerEvent.Previous)
                }

                Command.SkipToPrevious -> {
                    local.onPlayerEvent(PlayerEvent.SkipToPrevious)
                }

                is Command.SeekTo -> {
                    val duration = local.getPlayerDuration()
                    if (duration <= 0L) return@withContext "not_ready"
                    local.onPlayerEvent(PlayerEvent.UpdateProgress(command.positionMs.coerceIn(0L, duration) * 100f / duration))
                }

                Command.ToggleShuffle -> {
                    local.onPlayerEvent(PlayerEvent.Shuffle)
                }

                Command.CycleRepeat -> {
                    local.onPlayerEvent(PlayerEvent.Repeat)
                }

                Command.ToggleLike -> {
                    local.onPlayerEvent(PlayerEvent.ToggleLike)
                }

                Command.Dislike -> {
                    local.dislike()
                }

                is Command.SetVolume -> {
                    val volume = command.volume.coerceIn(0f, 1f)
                    systemVolume?.set(volume) ?: local.onPlayerEvent(PlayerEvent.UpdateVolume(volume))
                }

                is Command.PlayIndex -> {
                    val size = local.queueData.value?.data?.listTracks?.size ?: 0
                    if (command.index !in 0 until size) return@withContext "out_of_range"
                    local.playMediaItemInMediaSource(command.index)
                }

                is Command.RemoveAt -> {
                    local.removeMediaItem(command.position)
                }

                is Command.Move -> {
                    val size = local.queueData.value?.data?.listTracks?.size ?: 0
                    if (command.from !in 0 until size || command.to !in 0 until size) return@withContext "out_of_range"
                    local.swap(command.from, command.to)
                }

                is Command.PlayNext -> {
                    local.playNext(json.decodeFromString(Track.serializer(), command.trackJson))
                }

                is Command.AddToQueue -> {
                    local.addToQueue(json.decodeFromString(trackListSerializer, command.tracksJson))
                }

                is Command.PlayQueue -> {
                    val track = json.decodeFromString(Track.serializer(), command.trackJson)
                    val data = decodeQueue(command.queue)
                    local.reset()
                    local.setQueueData(data.copy(firstPlayedTrack = track))
                    local.loadMediaItem(track, command.clickType, command.index)
                    if (command.shuffle && !local.controlState.value.isShuffle) local.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }
            publishNow.trySend(Unit)
            null
        }

    override suspend fun onTransfer(
        from: DeviceInfo,
        player: PlayerState,
        queue: QueueState,
        play: Boolean,
    ): String? =
        withContext(playerContext) {
            if (!_enabled.value) return@withContext "off"
            takeOver(decodeQueue(queue), player.index, player.positionMs, player.repeat, play)
        }

    private suspend fun takeOver(
        queue: QueueData.Data,
        index: Int,
        positionMs: Long,
        repeatMode: Int,
        play: Boolean,
    ): String? {
        if (!local.loadSnapshot(PlaybackSnapshot(queue, index, positionMs, play))) return "nothing_to_play"
        _target.value = ControlTarget.ThisDevice
        _remote.value = null
        val wantedRepeat =
            when (repeatMode) {
                1 -> RepeatState.One
                2 -> RepeatState.All
                else -> RepeatState.None
            }
        repeat(3) { if (local.controlState.value.repeatState != wantedRepeat) local.onPlayerEvent(PlayerEvent.Repeat) }
        publishNow.trySend(Unit)
        if (play) {
            val playing =
                withTimeoutOrNull(TAKEOVER_TIMEOUT_MS) {
                    while (!(local.controlState.value.isPlaying && local.getProgress() >= positionMs + 250L)) delay(100)
                    true
                } ?: false
            if (!playing) {
                if (local.controlState.value.isPlaying) local.onPlayerEvent(PlayerEvent.PlayPause)
                return "did_not_start"
            }
        }
        _notices.tryEmit(ControlNotice.NowPlayingHere)
        return null
    }

    override suspend fun onTransferRequest(from: DeviceInfo): String? = if (transferTo(from.id)) null else "failed"

    override suspend fun transferTo(deviceId: String): Boolean =
        withContext(playerContext) {
            val engine = engine ?: return@withContext false
            val name = _devices.value.firstOrNull { it.id == deviceId }?.name ?: deviceId
            val state = currentState()
            val queue = local.queueData.value?.data
            if (state.trackId == null || queue == null || state.index !in queue.listTracks.indices) {
                _notices.tryEmit(ControlNotice.TransferFailed(name))
                return@withContext false
            }
            val error = engine.transfer(deviceId, state, queue.toQueueState(queueRev), play = state.isPlaying)
            if (error != null) {
                Logger.w(TAG, "Transfer to $name failed: $error")
                _notices.tryEmit(ControlNotice.TransferFailed(name))
                return@withContext false
            }
            if (local.controlState.value.isPlaying) local.onPlayerEvent(PlayerEvent.PlayPause)
            controlRemote(deviceId)
            _remote.update {
                it?.copy(isPlaying = state.isPlaying, positionMs = state.positionMs, positionAt = TimeSource.Monotonic.markNow(), index = state.index)
            }
            _notices.tryEmit(ControlNotice.NowPlayingOn(name))
            true
        }

    override suspend fun transferHere(deviceId: String): Boolean =
        withContext(playerContext) {
            val engine = engine ?: return@withContext false
            val device = _devices.value.firstOrNull { it.id == deviceId }
            val name = device?.name ?: deviceId
            if (device?.connected != true) {
                val last = engine.players.value[deviceId]
                val queue = last?.queue?.let { decodeQueue(it) }
                if (last != null && queue != null && takeOver(queue, last.state.index, last.state.positionMs, last.state.repeat, play = true) == null) {
                    return@withContext true
                }
                _notices.tryEmit(ControlNotice.Unreachable(name))
                return@withContext false
            }
            val error = engine.requestTransfer(deviceId)
            if (error != null) {
                Logger.w(TAG, "Transfer from $name failed: $error")
                _notices.tryEmit(ControlNotice.TransferFailed(name))
            }
            error == null
        }

    override fun controlRemote(deviceId: String) {
        autoDeviceId = null
        attach(deviceId)
    }

    private fun attach(deviceId: String) {
        val device = _devices.value.firstOrNull { it.id == deviceId && it.paired } ?: return
        _target.value = ControlTarget.Remote(device.id, device.name)
        _remote.value = engine?.players?.value?.get(deviceId)?.let { toPlayback(device.id, device.name, device.connected, it) }
            ?: emptyPlayback(device)
    }

    private fun emptyPlayback(device: ControlDevice) =
        RemotePlayback(
            deviceId = device.id,
            deviceName = device.name,
            connected = device.connected,
            track = null,
            queue = QueueData.Data(),
            index = -1,
            positionMs = 0L,
            positionAt = TimeSource.Monotonic.markNow(),
            durationMs = 0L,
            isPlaying = false,
            loading = false,
            shuffle = false,
            repeat = RepeatState.None,
            liked = false,
            volume = 1f,
            nextAvailable = false,
            previousAvailable = false,
        )

    override fun controlThisDevice() {
        (_target.value as? ControlTarget.Remote)?.let { left ->
            if (_devices.value.any { it.id == left.deviceId && it.isPlaying }) declined += left.deviceId
        }
        autoDeviceId = null
        _target.value = ControlTarget.ThisDevice
        _remote.value = null
    }

    private fun maybeAutoAttach() {
        if (!_enabled.value || _target.value !is ControlTarget.ThisDevice) return
        if (local.controlState.value.isPlaying) return
        val playing = _devices.value.firstOrNull { it.paired && it.connected && it.isPlaying && it.id !in declined } ?: return
        Logger.d(TAG, "Following ${playing.name}: it plays and this device is idle")
        autoDeviceId = playing.id
        attach(playing.id)
    }

    override suspend fun listenOn(deviceId: String?): Boolean {
        val current = _target.value as? ControlTarget.Remote
        if (deviceId == null) {
            current ?: return true
            if (_remote.value?.track == null) {
                controlThisDevice()
                return true
            }
            return transferHere(current.deviceId)
        }
        if (current?.deviceId == deviceId) {
            autoDeviceId = null
            return true
        }
        val device = _devices.value.firstOrNull { it.id == deviceId && it.paired } ?: return false
        if (current != null) {
            if (device.isPlaying || _remote.value?.track == null) {
                controlRemote(deviceId)
                return true
            }
            return transferHere(current.deviceId) && transferTo(deviceId)
        }
        val here = withContext(playerContext) { currentState() }
        return when {
            here.isPlaying -> transferTo(deviceId)
            device.isPlaying -> {
                controlRemote(deviceId)
                true
            }
            here.trackId != null -> transferTo(deviceId)
            else -> {
                controlRemote(deviceId)
                true
            }
        }
    }

    override fun send(command: RemoteCommand) {
        val target = _target.value as? ControlTarget.Remote ?: return
        val engine = engine ?: return
        val mirrored = _remote.value
        if (mirrored != null && !mirrored.connected) {
            if (command is RemoteCommand.Play) scope.launch { transferHere(target.deviceId) } else _notices.tryEmit(ControlNotice.Unreachable(target.deviceName))
            return
        }
        if (mirrored != null) {
            val now = TimeSource.Monotonic.markNow()
            when (command) {
                RemoteCommand.Play -> _remote.value = mirrored.copy(isPlaying = true, positionMs = mirrored.currentPositionMs(), positionAt = now)
                RemoteCommand.Pause -> _remote.value = mirrored.copy(isPlaying = false, positionMs = mirrored.currentPositionMs(), positionAt = now)
                is RemoteCommand.SeekTo -> _remote.value = mirrored.copy(positionMs = command.positionMs, positionAt = now)
                is RemoteCommand.SetVolume -> _remote.value = mirrored.copy(volume = command.volume)
                else -> Unit
            }
        }
        val wire =
            when (command) {
                RemoteCommand.Play -> Command.Play
                RemoteCommand.Pause -> Command.Pause
                RemoteCommand.Next -> Command.Next
                RemoteCommand.Previous -> Command.Previous
                RemoteCommand.SkipToPrevious -> Command.SkipToPrevious
                is RemoteCommand.SeekTo -> Command.SeekTo(command.positionMs)
                RemoteCommand.ToggleShuffle -> Command.ToggleShuffle
                RemoteCommand.CycleRepeat -> Command.CycleRepeat
                RemoteCommand.ToggleLike -> Command.ToggleLike
                RemoteCommand.Dislike -> Command.Dislike
                is RemoteCommand.SetVolume -> Command.SetVolume(command.volume)
                is RemoteCommand.PlayIndex -> Command.PlayIndex(command.index)
                is RemoteCommand.RemoveAt -> Command.RemoveAt(command.position)
                is RemoteCommand.Move -> Command.Move(command.from, command.to)
                is RemoteCommand.PlayNext -> Command.PlayNext(json.encodeToString(Track.serializer(), command.track))
                is RemoteCommand.AddToQueue -> Command.AddToQueue(json.encodeToString(trackListSerializer, command.tracks))
                is RemoteCommand.PlayQueue ->
                    Command.PlayQueue(
                        queue = command.queue.toQueueState(0),
                        trackJson = json.encodeToString(Track.serializer(), command.track),
                        clickType = command.clickType,
                        index = command.index,
                        shuffle = command.shuffle,
                    )
            }
        scope.launch {
            val error = engine.send(target.deviceId, wire)
            if (error != null) {
                Logger.w(TAG, "${wire::class.simpleName} on ${target.deviceName} failed: $error")
                if (error == com.maxrave.refinecontrol.ERROR_NOT_CONNECTED || error == com.maxrave.refinecontrol.ERROR_TIMEOUT) {
                    _notices.tryEmit(ControlNotice.Unreachable(target.deviceName))
                }
            }
        }
    }
}
