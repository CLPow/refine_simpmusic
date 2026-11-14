package com.maxrave.domain.repository

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeMark

enum class ControlDeviceType { PHONE, DESKTOP }

data class ControlDevice(
    val id: String,
    val name: String,
    val type: ControlDeviceType,
    val paired: Boolean,
    val connected: Boolean,
    val playingTitle: String?,
    val isPlaying: Boolean,
)

sealed interface ControlTarget {
    data object ThisDevice : ControlTarget

    data class Remote(
        val deviceId: String,
        val deviceName: String,
    ) : ControlTarget
}

data class ControlPairingPrompt(
    val deviceName: String,
    val code: String,
    val waitingForOther: Boolean,
)

data class RemotePlayback(
    val deviceId: String,
    val deviceName: String,
    val connected: Boolean,
    val track: Track?,
    val queue: QueueData.Data,
    val index: Int,
    val positionMs: Long,
    val positionAt: TimeMark,
    val durationMs: Long,
    val isPlaying: Boolean,
    val loading: Boolean,
    val shuffle: Boolean,
    val repeat: RepeatState,
    val liked: Boolean,
    val volume: Float,
    val nextAvailable: Boolean,
    val previousAvailable: Boolean,
) {
    fun currentPositionMs(): Long {
        val position = if (isPlaying && !loading) positionMs + positionAt.elapsedNow().inWholeMilliseconds else positionMs
        return if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)
    }
}

sealed interface RemoteCommand {
    data object Play : RemoteCommand

    data object Pause : RemoteCommand

    data object Next : RemoteCommand

    data object Previous : RemoteCommand

    data object SkipToPrevious : RemoteCommand

    data class SeekTo(
        val positionMs: Long,
    ) : RemoteCommand

    data object ToggleShuffle : RemoteCommand

    data object CycleRepeat : RemoteCommand

    data object ToggleLike : RemoteCommand

    data object Dislike : RemoteCommand

    data class SetVolume(
        val volume: Float,
    ) : RemoteCommand

    data class PlayIndex(
        val index: Int,
    ) : RemoteCommand

    data class RemoveAt(
        val position: Int,
    ) : RemoteCommand

    data class Move(
        val from: Int,
        val to: Int,
    ) : RemoteCommand

    data class PlayNext(
        val track: Track,
    ) : RemoteCommand

    data class AddToQueue(
        val tracks: List<Track>,
    ) : RemoteCommand

    data class PlayQueue(
        val queue: QueueData.Data,
        val track: Track,
        val clickType: String,
        val index: Int?,
        val shuffle: Boolean = false,
    ) : RemoteCommand
}

sealed interface ControlNotice {
    data class Unreachable(
        val deviceName: String,
    ) : ControlNotice

    data class TransferFailed(
        val deviceName: String,
    ) : ControlNotice

    data class NowPlayingOn(
        val deviceName: String,
    ) : ControlNotice

    data object NowPlayingHere : ControlNotice

    data class Paired(
        val deviceName: String,
    ) : ControlNotice

    data class Disconnected(
        val deviceName: String,
    ) : ControlNotice
}

interface RefineControlRepository {
    val isSupported: Boolean

    val enabled: StateFlow<Boolean>
    val deviceName: StateFlow<String>
    val devices: StateFlow<List<ControlDevice>>
    val target: StateFlow<ControlTarget>

    val remote: StateFlow<RemotePlayback?>
    val pairing: StateFlow<ControlPairingPrompt?>
    val notices: SharedFlow<ControlNotice>

    fun setEnabled(enabled: Boolean)

    fun setDeviceName(name: String)

    fun setScanning(scanning: Boolean)

    fun startPairing(deviceId: String)

    fun confirmPairing()

    fun cancelPairing()

    fun forget(deviceId: String)

    fun controlRemote(deviceId: String)

    fun controlThisDevice()

    suspend fun transferTo(deviceId: String): Boolean

    suspend fun transferHere(deviceId: String): Boolean

    suspend fun listenOn(deviceId: String?): Boolean = false

    val autoAttached: Boolean get() = false

    suspend fun connectByAddress(address: String): Boolean

    fun send(command: RemoteCommand)
}

class NoRefineControlRepository : RefineControlRepository {
    override val isSupported: Boolean = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val deviceName: StateFlow<String> = MutableStateFlow("")
    override val devices: StateFlow<List<ControlDevice>> = MutableStateFlow(emptyList())
    override val target: StateFlow<ControlTarget> = MutableStateFlow(ControlTarget.ThisDevice)
    override val remote: StateFlow<RemotePlayback?> = MutableStateFlow(null)
    override val pairing: StateFlow<ControlPairingPrompt?> = MutableStateFlow(null)
    override val notices: SharedFlow<ControlNotice> = MutableSharedFlow()

    override fun setEnabled(enabled: Boolean) = Unit

    override fun setDeviceName(name: String) = Unit

    override fun setScanning(scanning: Boolean) = Unit

    override fun startPairing(deviceId: String) = Unit

    override fun confirmPairing() = Unit

    override fun cancelPairing() = Unit

    override fun forget(deviceId: String) = Unit

    override fun controlRemote(deviceId: String) = Unit

    override fun controlThisDevice() = Unit

    override suspend fun transferTo(deviceId: String): Boolean = false

    override suspend fun transferHere(deviceId: String): Boolean = false

    override suspend fun connectByAddress(address: String): Boolean = false

    override fun send(command: RemoteCommand) = Unit
}
