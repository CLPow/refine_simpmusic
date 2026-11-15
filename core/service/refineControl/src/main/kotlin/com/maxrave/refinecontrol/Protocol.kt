package com.maxrave.refinecontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1

const val DEFAULT_DISCOVERY_PORT = 45654

const val BEACON_APP = "refine-control"

const val DEVICE_PHONE = "phone"
const val DEVICE_DESKTOP = "desktop"

internal val wireJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val type: String,
    val proto: Int = PROTOCOL_VERSION,
)

@Serializable
data class Beacon(
    val app: String = BEACON_APP,
    val device: DeviceInfo,
    val tcpPort: Int,
    val reply: Boolean = false,
)

@Serializable
data class PlayerState(
    val trackId: String? = null,
    val trackJson: String? = null,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val index: Int = -1,
    val queueRev: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val liked: Boolean = false,
    val volume: Float = 1f,
    val nextAvailable: Boolean = false,
    val prevAvailable: Boolean = false,
    val playEpoch: Long = 0,
)

@Serializable
data class QueueState(
    val rev: Long = 0,
    val tracksJson: String = "[]",
    val playlistId: String? = null,
    val playlistName: String? = null,
    val playlistType: String? = null,
    val continuation: String? = null,
    val autoplayStartIndex: Int? = null,
)

@Serializable
sealed class Command {
    @Serializable
    @SerialName("play")
    data object Play : Command()

    @Serializable
    @SerialName("pause")
    data object Pause : Command()

    @Serializable
    @SerialName("next")
    data object Next : Command()

    @Serializable
    @SerialName("previous")
    data object Previous : Command()

    @Serializable
    @SerialName("skip_to_previous")
    data object SkipToPrevious : Command()

    @Serializable
    @SerialName("seek")
    data class SeekTo(
        val positionMs: Long,
    ) : Command()

    @Serializable
    @SerialName("resume")
    data class Resume(
        val positionMs: Long,
    ) : Command()

    @Serializable
    @SerialName("shuffle")
    data object ToggleShuffle : Command()

    @Serializable
    @SerialName("repeat")
    data object CycleRepeat : Command()

    @Serializable
    @SerialName("like")
    data object ToggleLike : Command()

    @Serializable
    @SerialName("dislike")
    data object Dislike : Command()

    @Serializable
    @SerialName("volume")
    data class SetVolume(
        val volume: Float,
    ) : Command()

    @Serializable
    @SerialName("play_index")
    data class PlayIndex(
        val index: Int,
    ) : Command()

    @Serializable
    @SerialName("remove")
    data class RemoveAt(
        val position: Int,
    ) : Command()

    @Serializable
    @SerialName("move")
    data class Move(
        val from: Int,
        val to: Int,
    ) : Command()

    @Serializable
    @SerialName("play_next")
    data class PlayNext(
        val trackJson: String,
    ) : Command()

    @Serializable
    @SerialName("add_to_queue")
    data class AddToQueue(
        val tracksJson: String,
    ) : Command()

    @Serializable
    @SerialName("play_queue")
    data class PlayQueue(
        val queue: QueueState,
        val trackJson: String,
        val clickType: String,
        val index: Int? = null,
        val shuffle: Boolean = false,
    ) : Command()
}

@Serializable
internal sealed class Wire {
    @Serializable
    @SerialName("pair_start")
    data class PairStart(
        val device: DeviceInfo,
        val commit: String,
    ) : Wire()

    @Serializable
    @SerialName("pair_key")
    data class PairKey(
        val device: DeviceInfo,
        val pub: String,
        val nonce: String,
    ) : Wire()

    @Serializable
    @SerialName("pair_reveal")
    data class PairReveal(
        val pub: String,
        val nonce: String,
    ) : Wire()

    @Serializable
    @SerialName("auth_start")
    data class AuthStart(
        val device: DeviceInfo,
        val nonce: String,
    ) : Wire()

    @Serializable
    @SerialName("auth_reply")
    data class AuthReply(
        val device: DeviceInfo,
        val nonce: String,
    ) : Wire()

    @Serializable
    @SerialName("reject")
    data class Reject(
        val reason: String,
    ) : Wire()

    @Serializable
    @SerialName("pair_confirm")
    data object PairConfirm : Wire()

    @Serializable
    @SerialName("pair_cancel")
    data object PairCancel : Wire()

    @Serializable
    @SerialName("ready")
    data class Ready(
        val device: DeviceInfo,
    ) : Wire()

    @Serializable
    @SerialName("ping")
    data object Ping : Wire()

    @Serializable
    @SerialName("pong")
    data object Pong : Wire()

    @Serializable
    @SerialName("bye")
    data object Bye : Wire()

    @Serializable
    @SerialName("unpair")
    data object Unpair : Wire()

    @Serializable
    @SerialName("state")
    data class State(
        val player: PlayerState,
    ) : Wire()

    @Serializable
    @SerialName("queue")
    data class Queue(
        val queue: QueueState,
    ) : Wire()

    @Serializable
    @SerialName("command")
    data class Cmd(
        val id: Long,
        val command: Command,
    ) : Wire()

    @Serializable
    @SerialName("transfer")
    data class Transfer(
        val id: Long,
        val player: PlayerState,
        val queue: QueueState,
        val play: Boolean,
    ) : Wire()

    @Serializable
    @SerialName("request_transfer")
    data class RequestTransfer(
        val id: Long,
    ) : Wire()

    @Serializable
    @SerialName("ack")
    data class Ack(
        val id: Long,
        val ok: Boolean,
        val error: String? = null,
    ) : Wire()
}

internal fun Wire.encode(): String = wireJson.encodeToString(Wire.serializer(), this)

internal fun decodeWire(line: String): Wire = wireJson.decodeFromString(Wire.serializer(), line)
