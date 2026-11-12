package com.maxrave.refinecontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Raised when a message changes in a way older builds cannot read. */
const val PROTOCOL_VERSION = 1

/** UDP port of the discovery beacon. The TCP port is picked by the system and sent in the beacon. */
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

/** Sent by UDP: "I am here, and this is my TCP port". [reply] marks the direct answer to one. */
@Serializable
data class Beacon(
    val app: String = BEACON_APP,
    val device: DeviceInfo,
    val tcpPort: Int,
    val reply: Boolean = false,
)

/**
 * What a device is playing. The device that plays sends this; a controller only mirrors it.
 *
 * [positionMs] is the position when the message was sent. The receiver notes when it arrived and
 * counts forward from there while [isPlaying], so no message is needed per second and the two
 * clocks never have to agree.
 */
@Serializable
data class PlayerState(
    val trackId: String? = null,
    /** The playing track as the app's own Track JSON; opaque to this module. */
    val trackJson: String? = null,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    /** Place of the playing track in the queue, in play order. */
    val index: Int = -1,
    /** The [QueueState.rev] this state belongs to. */
    val queueRev: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val shuffle: Boolean = false,
    /** 0 off, 1 one, 2 all. */
    val repeat: Int = 0,
    val liked: Boolean = false,
    val volume: Float = 1f,
    val nextAvailable: Boolean = false,
    val prevAvailable: Boolean = false,
)

/** The queue, sent only when it changes: it is the large part. */
@Serializable
data class QueueState(
    val rev: Long = 0,
    /** JSON array of the app's Track, in play order; opaque to this module. */
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

    /** Start something new: the queue, the track to begin with, and how it was picked. */
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

/** Every line on the TCP connection is one of these. */
@Serializable
internal sealed class Wire {
    // ---- Pairing handshake (plain text; nothing secret is sent before the code is confirmed)

    /** [commit] = SHA-256(public key ‖ nonce): fixes the caller's key before it sees ours. */
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

    // ---- Connection between paired devices (plain text nonces, then everything is encrypted)

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

    // ---- Encrypted from here on

    @Serializable
    @SerialName("pair_confirm")
    data object PairConfirm : Wire()

    @Serializable
    @SerialName("pair_cancel")
    data object PairCancel : Wire()

    /** First encrypted message each way: being able to read it proves the sender holds the key. */
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

    /** "Forget this device" on the other side: drop the pairing here as well. */
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

    /** Hand playback over: load this, continue from [PlayerState.positionMs], play if [play]. */
    @Serializable
    @SerialName("transfer")
    data class Transfer(
        val id: Long,
        val player: PlayerState,
        val queue: QueueState,
        val play: Boolean,
    ) : Wire()

    /** "Play on me": asks the receiver to hand its playback to the sender. */
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
