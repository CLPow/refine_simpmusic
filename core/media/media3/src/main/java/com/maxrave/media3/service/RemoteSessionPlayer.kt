package com.maxrave.media3.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.domain.repository.RemoteCommand
import com.maxrave.domain.repository.RemotePlayback
import com.maxrave.media3.exoplayer.toMedia3MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CONFIRM_TIMEOUT_MS = 4_000L

private const val VOLUME_STEPS = 20

private val REPEAT_CYCLE = listOf(RepeatState.None, RepeatState.All, RepeatState.One)

@UnstableApi
internal class RemoteSessionPlayer(
    private val control: RefineControlRepository,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val watchJob: Job =
        scope.launch {
            control.remote.collect { invalidateState() }
        }

    val isActive: Boolean get() = control.remote.value?.let { it.connected && it.track != null } == true

    override fun getState(): State {
        val remote = control.remote.value?.takeIf { it.connected && it.track != null } ?: return State.Builder().build()
        val track = remote.track!!
        val queue = remote.queue.listTracks
        val inQueue = remote.index in queue.indices && queue[remote.index].videoId == track.videoId
        val tracks = if (inQueue) queue else listOf(track)
        val current = if (inQueue) remote.index else 0
        val playlist =
            tracks.mapIndexed { i, t ->
                MediaItemData
                    .Builder("$i:${t.videoId}")
                    .setMediaItem(t.toGenericMediaItem().toMedia3MediaItem())
                    .setDurationUs(if (i == current && remote.durationMs > 0L) remote.durationMs * 1000L else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
        val playing = remote.isPlaying && !remote.loading
        return State
            .Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(current)
            .setContentPositionMs(PositionSupplier.getExtrapolating(remote.currentPositionMs(), if (playing) 1f else 0f))
            .setPlayWhenReady(remote.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (remote.loading) STATE_BUFFERING else STATE_READY)
            .setIsLoading(remote.loading)
            .setDeviceInfo(REMOTE_DEVICE)
            .setDeviceVolume(stepsOf(remote.volume))
            .setShuffleModeEnabled(remote.shuffle)
            .setRepeatMode(
                when (remote.repeat) {
                    RepeatState.None -> REPEAT_MODE_OFF
                    RepeatState.One -> REPEAT_MODE_ONE
                    RepeatState.All -> REPEAT_MODE_ALL
                },
            ).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        control.send(if (playWhenReady) RemoteCommand.Play else RemoteCommand.Pause)
        return confirmed { it.isPlaying == playWhenReady }
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        val before = control.remote.value ?: return Futures.immediateVoidFuture()
        val currentIndex = currentMediaItemIndex
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> control.send(RemoteCommand.Next)
            COMMAND_SEEK_TO_PREVIOUS -> control.send(RemoteCommand.Previous)
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> control.send(RemoteCommand.SkipToPrevious)
            else -> {
                if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != currentIndex && before.queue.listTracks.isNotEmpty()) {
                    control.send(RemoteCommand.PlayIndex(mediaItemIndex))
                } else {
                    val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
                    control.send(RemoteCommand.SeekTo(target))
                    return confirmed { kotlin.math.abs(it.currentPositionMs() - target) < 2_000L || it.track != before.track }
                }
            }
        }
        return confirmed { it.track != before.track || it.index != before.index || it.positionMs != before.positionMs }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (control.remote.value?.shuffle == shuffleModeEnabled) return Futures.immediateVoidFuture()
        control.send(RemoteCommand.ToggleShuffle)
        return confirmed { it.shuffle == shuffleModeEnabled }
    }

    override fun handleSetRepeatMode(repeatMode: @Player.RepeatMode Int): ListenableFuture<*> {
        val wanted =
            when (repeatMode) {
                REPEAT_MODE_ONE -> RepeatState.One
                REPEAT_MODE_ALL -> RepeatState.All
                else -> RepeatState.None
            }
        val from = REPEAT_CYCLE.indexOf(control.remote.value?.repeat ?: return Futures.immediateVoidFuture())
        val steps = (REPEAT_CYCLE.indexOf(wanted) - from + REPEAT_CYCLE.size) % REPEAT_CYCLE.size
        repeat(steps) { control.send(RemoteCommand.CycleRepeat) }
        return if (steps == 0) Futures.immediateVoidFuture() else confirmed { it.repeat == wanted }
    }

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: @C.VolumeFlags Int,
    ): ListenableFuture<*> = setRemoteVolume(deviceVolume)

    override fun handleIncreaseDeviceVolume(flags: @C.VolumeFlags Int): ListenableFuture<*> = setRemoteVolume(deviceVolume + 1)

    override fun handleDecreaseDeviceVolume(flags: @C.VolumeFlags Int): ListenableFuture<*> = setRemoteVolume(deviceVolume - 1)

    private fun setRemoteVolume(steps: Int): ListenableFuture<*> {
        val wanted = steps.coerceIn(0, VOLUME_STEPS)
        control.send(RemoteCommand.SetVolume(wanted / VOLUME_STEPS.toFloat()))
        return confirmed { stepsOf(it.volume) == wanted }
    }

    private fun stepsOf(volume: Float): Int = Math.round(volume.coerceIn(0f, 1f) * VOLUME_STEPS)

    override fun handleStop(): ListenableFuture<*> {
        control.send(RemoteCommand.Pause)
        return confirmed { !it.isPlaying }
    }

    override fun handleRelease(): ListenableFuture<*> {
        watchJob.cancel()
        return Futures.immediateVoidFuture()
    }

    fun toggleLike() = control.send(RemoteCommand.ToggleLike)

    fun toggleShuffle() = control.send(RemoteCommand.ToggleShuffle)

    private fun confirmed(done: (RemotePlayback) -> Boolean): ListenableFuture<*> {
        val future = SettableFuture.create<Unit>()
        scope.launch {
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { control.remote.first { it == null || done(it) } }
            future.set(Unit)
        }
        return future
    }

    private companion object {
        const val PLAY_WHEN_READY_CHANGE_REASON_REMOTE = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE

        val REMOTE_DEVICE: DeviceInfo =
            DeviceInfo
                .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(VOLUME_STEPS)
                .build()

        val COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_DEFAULT_POSITION,
                    COMMAND_SEEK_TO_MEDIA_ITEM,
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_SEEK_BACK,
                    COMMAND_SEEK_FORWARD,
                    COMMAND_SET_SHUFFLE_MODE,
                    COMMAND_SET_REPEAT_MODE,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_METADATA,
                    COMMAND_GET_DEVICE_VOLUME,
                    COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                ).build()
    }
}
