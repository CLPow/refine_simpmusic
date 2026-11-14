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

/** Another Refine device on the network. */
data class ControlDevice(
    val id: String,
    val name: String,
    val type: ControlDeviceType,
    val paired: Boolean,
    /** Paired and reachable right now: it can be controlled, and playback can move to it. */
    val connected: Boolean,
    /** What it plays, when it plays something. */
    val playingTitle: String?,
    val isPlaying: Boolean,
)

/** Which player the app's controls act on. */
sealed interface ControlTarget {
    data object ThisDevice : ControlTarget

    data class Remote(
        val deviceId: String,
        val deviceName: String,
    ) : ControlTarget
}

/** The code to compare with the other device's screen. */
data class ControlPairingPrompt(
    val deviceName: String,
    val code: String,
    /** Confirmed here; the other device has not confirmed yet. */
    val waitingForOther: Boolean,
)

/**
 * What the controlled device is playing, as last reported by it.
 *
 * [positionMs] was true at [positionAt]; while [isPlaying] the position now is
 * `positionMs + positionAt.elapsedNow()` ([currentPositionMs]).
 */
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

/** What a controller can ask the controlled device to do. */
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

    /** [index] is a place in the queue as shown. */
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

    /** Start something new there, the way a tap on a song, album or playlist does here. */
    data class PlayQueue(
        val queue: QueueData.Data,
        val track: Track,
        val clickType: String,
        val index: Int?,
        val shuffle: Boolean = false,
    ) : RemoteCommand
}

/** Something to tell the user in a toast. */
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

/**
 * Refine Control: control another Refine device on the same network, and move playback between
 * devices (the way Spotify Connect does). On unless switched off in Settings; devices still have to
 * be paired once with a code before they can see each other's music.
 */
interface RefineControlRepository {
    /** False on platforms without Refine Control; the UI hides everything then. */
    val isSupported: Boolean

    val enabled: StateFlow<Boolean>
    val deviceName: StateFlow<String>
    val devices: StateFlow<List<ControlDevice>>
    val target: StateFlow<ControlTarget>

    /** The player being controlled; null while [target] is this device. */
    val remote: StateFlow<RemotePlayback?>
    val pairing: StateFlow<ControlPairingPrompt?>
    val notices: SharedFlow<ControlNotice>

    fun setEnabled(enabled: Boolean)

    fun setDeviceName(name: String)

    /** The devices list is on screen: look harder for devices. */
    fun setScanning(scanning: Boolean)

    fun startPairing(deviceId: String)

    fun confirmPairing()

    fun cancelPairing()

    fun forget(deviceId: String)

    /** The app's controls act on [deviceId] from now on; nothing changes about what plays where. */
    fun controlRemote(deviceId: String)

    fun controlThisDevice()

    /** Moves what this device plays to [deviceId], and controls it there. False when it failed. */
    suspend fun transferTo(deviceId: String): Boolean

    /** Moves what [deviceId] plays to this device. False when it failed. */
    suspend fun transferHere(deviceId: String): Boolean

    /**
     * One tap in the devices list: listen on [deviceId] (null = this device). Moves the music
     * there when it plays somewhere else, or just takes control when that device is the one
     * already playing. False when it failed.
     */
    suspend fun listenOn(deviceId: String?): Boolean = false

    /**
     * True while [target] was picked automatically (this device was idle and a paired device was
     * playing). Starting something new on this device then plays it here instead of over there.
     */
    val autoAttached: Boolean get() = false

    /** For networks where the devices do not find each other. True when a device answered. */
    suspend fun connectByAddress(address: String): Boolean

    /** Sends [command] to the controlled device. Nothing happens while [target] is this device. */
    fun send(command: RemoteCommand)
}

/** Refine Control where there is none (iOS for now): always off. */
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
