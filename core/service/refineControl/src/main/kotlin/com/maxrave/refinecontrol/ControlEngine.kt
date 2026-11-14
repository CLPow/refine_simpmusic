package com.maxrave.refinecontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Another Refine device, as the devices list shows it. */
data class PeerDevice(
    val id: String,
    val name: String,
    val type: String,
    val paired: Boolean,
    /** An encrypted connection to it is open. */
    val connected: Boolean,
    /** Its beacon was heard in the last few seconds. */
    val visible: Boolean,
    val host: String?,
)

/** The last thing a connected device said it is playing. */
data class RemotePlayer(
    val state: PlayerState,
    val queue: QueueState?,
    /** `System.nanoTime()` when [state] arrived; the position is counted forward from here. */
    val receivedAtNanos: Long,
)

/** The code to compare with the other screen. */
data class PairingPrompt(
    val deviceId: String,
    val deviceName: String,
    val code: String,
    /** Confirmed on this device; waiting for the other one. */
    val confirmedHere: Boolean,
)

@Serializable
data class PairedPeer(
    val id: String,
    val name: String,
    val type: String,
    /** Base64 of the 32-byte secret agreed while pairing. */
    val key: String,
    val lastHost: String? = null,
)

/** Where the paired devices are kept. The caller decides how (and encrypts them). */
interface PairStore {
    fun load(): List<PairedPeer>

    fun save(peers: List<PairedPeer>)
}

/** This device's own player, as the engine reaches it. Each call returns null, or what went wrong. */
interface LocalPlayerPort {
    suspend fun onCommand(
        from: DeviceInfo,
        command: Command,
    ): String?

    /** Returns once playback has been taken over (when asked to play: once audio really plays). */
    suspend fun onTransfer(
        from: DeviceInfo,
        player: PlayerState,
        queue: QueueState,
        play: Boolean,
    ): String?

    /** [from] wants to play what this device plays. */
    suspend fun onTransferRequest(from: DeviceInfo): String?
}

private const val BEACON_INTERVAL_MS = 3_000L
private const val SEEN_TTL_NANOS = 12_000_000_000L
private const val REPLY_GAP_NANOS = 1_500_000_000L
private const val CONNECT_TIMEOUT_MS = 4_000
private const val HANDSHAKE_TIMEOUT_MS = 10_000
private const val PAIRING_TIMEOUT_MS = 120_000
private const val IDLE_TIMEOUT_MS = 30_000
private const val PING_INTERVAL_MS = 10_000L

/** How long the device with the higher id waits for the other one to call first. */
private const val DIAL_YIELD_MS = 2_500L

/** Connections that have not proved who they are yet; more than this are turned away. */
private const val MAX_UNPROVEN_CONNECTIONS = 16

/** After a pairing request was declined here, further ones are refused for this long. */
private const val PAIRING_COOLDOWN_NANOS = 8_000_000_000L
private const val HANDSHAKE_LINE_MAX = 8 * 1024
private const val LINE_MAX = 16 * 1024 * 1024

const val ERROR_NOT_CONNECTED = "not_connected"
const val ERROR_TIMEOUT = "timeout"

/**
 * Refine Control on one device: finds other Refine devices on the network, pairs with them, keeps
 * an encrypted connection to each paired one, and carries player state, commands and transfers.
 *
 * It works the way Spotify Connect does, without a server:
 * - Every device tells its peers what it is playing ([publishState], [publishQueue]). A controller
 *   mirrors that; audio is never sent. Each device fetches its own stream.
 * - A controller sends a [Command]; the playing device runs it and its next state shows the result.
 * - A transfer hands the other device the queue and position; it loads them and plays.
 *
 * Discovery is a UDP beacon every few seconds, to the broadcast address and directly to the last
 * known address of each paired device. Whoever hears one answers the sender directly, so finding
 * each other needs only one of the two to receive broadcasts. Both devices listen on TCP and both
 * may call, so it also works when one of them cannot be reached from outside (a firewall).
 */
class ControlEngine(
    private val scope: CoroutineScope,
    self: DeviceInfo,
    private val store: PairStore,
    private val port: LocalPlayerPort,
    private val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    private val log: (String) -> Unit = {},
) {
    @Volatile
    var self: DeviceInfo = self
        private set

    private class Seen(
        val device: DeviceInfo,
        val host: String,
        val tcpPort: Int,
        val atNanos: Long,
        val repliedAtNanos: Long,
    )

    private class Pending(
        val peerId: String,
        val answer: CompletableDeferred<Wire.Ack>,
    )

    private class PairingSession(
        val conn: Conn,
        val peer: DeviceInfo,
        val key: ByteArray,
        val code: String,
    ) {
        var here = false
        var there = false
        var done = false
    }

    private val lock = Any()
    private val paired = LinkedHashMap<String, PairedPeer>()
    private val seen = HashMap<String, Seen>()
    private val connections = HashMap<String, Conn>()
    private val dialing = HashSet<String>()
    private val extraTargets = HashSet<InetSocketAddress>()
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val nextId = AtomicLong(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var engineScope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var pairingSession: PairingSession? = null
    private val unproven = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var pairingRefusedUntilNanos = 0L

    @Volatile
    private var localState: PlayerState? = null

    @Volatile
    private var localQueue: QueueState? = null

    private val _devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val devices: StateFlow<List<PeerDevice>> = _devices.asStateFlow()

    private val _players = MutableStateFlow<Map<String, RemotePlayer>>(emptyMap())

    /** What each connected device is playing, by device id. Kept after a disconnect. */
    val players: StateFlow<Map<String, RemotePlayer>> = _players.asStateFlow()

    private val _pairing = MutableStateFlow<PairingPrompt?>(null)
    val pairing: StateFlow<PairingPrompt?> = _pairing.asStateFlow()

    val isRunning: Boolean get() = synchronized(lock) { engineScope != null }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        val running =
            synchronized(lock) {
                if (engineScope != null) return
                paired.clear()
                runCatching { store.load() }.getOrDefault(emptyList()).forEach { paired[it.id] = it }
                CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
                    .also { engineScope = it }
            }
        refreshDevices()
        running.launch {
            val tcp =
                try {
                    ServerSocket(0)
                } catch (e: IOException) {
                    log("Cannot listen: ${e.message}")
                    return@launch
                }
            val datagrams = openDiscoverySocket()
            val stale =
                synchronized(lock) {
                    if (engineScope !== running) {
                        true
                    } else {
                        server = tcp
                        udp = datagrams
                        false
                    }
                }
            if (stale) {
                tcp.closeQuietly()
                datagrams.close()
                return@launch
            }
            log("Listening on TCP ${tcp.localPort}, discovery on UDP ${datagrams.localPort}")
            launch { acceptLoop(tcp) }
            launch { receiveLoop(datagrams, tcp.localPort) }
            launch { beaconLoop(datagrams, tcp.localPort) }
        }
    }

    fun stop() {
        val (running, open) =
            synchronized(lock) {
                val running = engineScope ?: return
                engineScope = null
                val open = connections.values.toList()
                connections.clear()
                seen.clear()
                dialing.clear()
                extraTargets.clear()
                running to open
            }
        open.forEach { conn ->
            runCatching { conn.writeNow(Wire.Bye) }
            conn.close()
        }
        pairingSession?.conn?.close()
        server?.closeQuietly()
        udp?.close()
        server = null
        udp = null
        running.cancel()
        pending.values.forEach { it.answer.complete(Wire.Ack(0, false, ERROR_NOT_CONNECTED)) }
        pending.clear()
        _pairing.value = null
        _players.value = emptyMap()
        refreshDevices()
    }

    /** The name other devices show for this one. */
    fun rename(name: String) {
        self = self.copy(name = name)
        wake.trySend(Unit)
    }

    /** Send a beacon now instead of at the next tick (the devices list was opened). */
    fun announceNow() {
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------ discovery

    private fun openDiscoverySocket(): DatagramSocket {
        val socket = DatagramSocket(null as SocketAddress?)
        return try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(discoveryPort))
            socket
        } catch (e: IOException) {
            // The port is taken (another app, or a second Refine that does not share it). Beacons
            // can still be sent from any port, and the answers come back to it.
            log("Discovery port $discoveryPort is taken: ${e.message}")
            socket.close()
            DatagramSocket().apply { broadcast = true }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        runCatching { result += InetAddress.getByName("255.255.255.255") }
        runCatching {
            for (network in NetworkInterface.getNetworkInterfaces()) {
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
            }
        }
        return result.toList()
    }

    private fun sendBeacon(
        socket: DatagramSocket,
        tcpPort: Int,
        to: InetSocketAddress,
        reply: Boolean,
    ) {
        val bytes = wireJson.encodeToString(Beacon.serializer(), Beacon(device = self, tcpPort = tcpPort, reply = reply)).toByteArray()
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, to)) }
    }

    private suspend fun beaconLoop(
        socket: DatagramSocket,
        tcpPort: Int,
    ) {
        while (true) {
            val targets = LinkedHashSet<InetSocketAddress>()
            broadcastAddresses().forEach { targets += InetSocketAddress(it, discoveryPort) }
            synchronized(lock) {
                // Straight to where each paired device was last seen: networks that drop broadcast
                // still deliver this.
                paired.values.forEach { peer ->
                    if (peer.id !in connections) peer.lastHost?.let { targets += InetSocketAddress(it, discoveryPort) }
                }
                targets += extraTargets
            }
            targets.forEach { if (!it.isUnresolved) sendBeacon(socket, tcpPort, it, reply = false) }
            val now = System.nanoTime()
            val dropped = synchronized(lock) { seen.entries.removeAll { now - it.value.atNanos > SEEN_TTL_NANOS } }
            if (dropped) refreshDevices()
            withTimeoutOrNull(BEACON_INTERVAL_MS) { wake.receive() }
        }
    }

    private fun receiveLoop(
        socket: DatagramSocket,
        tcpPort: Int,
    ) {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                if (socket.isClosed) return
                continue
            }
            val beacon =
                runCatching { wireJson.decodeFromString(Beacon.serializer(), String(packet.data, packet.offset, packet.length)) }
                    .getOrNull() ?: continue
            if (beacon.app != BEACON_APP || beacon.device.id == self.id || beacon.device.id.isBlank()) continue
            if (beacon.device.proto != PROTOCOL_VERSION || beacon.tcpPort !in 1..65535) continue
            val host = packet.address.hostAddress ?: continue
            val now = System.nanoTime()
            val id = beacon.device.id
            var answer = false
            var changed = false
            synchronized(lock) {
                val before = seen[id]
                // Answer the sender directly, so it finds this device even when this device's own
                // broadcasts do not reach it. Not an answer to an answer, and not more often than
                // needed.
                answer = !beacon.reply && (before == null || now - before.repliedAtNanos > REPLY_GAP_NANOS)
                seen[id] = Seen(beacon.device, host, beacon.tcpPort, now, if (answer) now else before?.repliedAtNanos ?: 0L)
                changed = before == null || before.device != beacon.device || before.host != host
                val known = paired[id]
                if (known != null && (known.name != beacon.device.name || known.type != beacon.device.type)) {
                    paired[id] = known.copy(name = beacon.device.name, type = beacon.device.type)
                    savePaired()
                }
            }
            if (answer) sendBeacon(socket, tcpPort, InetSocketAddress(packet.address, packet.port), reply = true)
            if (changed) refreshDevices()
            connectIfPaired(id)
        }
    }

    /**
     * Looks for a device at [address] ("192.168.1.20", or with ":port"), for networks where the
     * devices do not find each other. True when it answered.
     */
    suspend fun connectByAddress(address: String): Boolean {
        val running = synchronized(lock) { engineScope } ?: return false
        val text = address.trim()
        val hostPart = text.substringBeforeLast(':', text).takeIf { text.count { c -> c == ':' } == 1 } ?: text
        val portPart = if (hostPart != text) text.substringAfterLast(':').toIntOrNull() else null
        val found = CompletableDeferred<Boolean>()
        running.launch {
            val target =
                runCatching { InetSocketAddress(InetAddress.getByName(hostPart), portPart ?: discoveryPort) }.getOrNull()
            if (target == null || target.isUnresolved) {
                found.complete(false)
                return@launch
            }
            synchronized(lock) { extraTargets += target }
            val wanted = target.address.hostAddress
            repeat(8) {
                wake.trySend(Unit)
                delay(500)
                if (synchronized(lock) { seen.values.any { it.host == wanted } }) {
                    found.complete(true)
                    return@launch
                }
            }
            found.complete(false)
        }
        return found.await()
    }

    // ------------------------------------------------------------------ connections

    private inner class Conn(
        val socket: Socket,
    ) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream(), 16 * 1024)
        private val output = socket.getOutputStream()
        val outbox = Channel<Wire>(Channel.UNLIMITED)

        @Volatile
        var cipher: FrameCipher? = null

        @Volatile
        var peer: DeviceInfo? = null

        /** Called once the other side has proved it holds the pairing key. */
        var onProven: () -> Unit = {}

        val host: String? get() = socket.inetAddress?.hostAddress

        /** Blocking. Sealing happens under the same lock as writing, so frames leave in order. */
        fun writeNow(message: Wire) {
            synchronized(output) {
                val json = message.encode().toByteArray()
                val line = cipher?.let { Base64.getEncoder().encode(it.seal(json)) } ?: json
                output.write(line)
                output.write('\n'.code)
                output.flush()
            }
        }

        /** Blocking. Throws when the connection ends or a frame does not open. */
        fun readNow(maxBytes: Int): Wire {
            val line = readLine(input, maxBytes) ?: throw EOFException()
            val json = cipher?.open(Base64.getDecoder().decode(line)) ?: line
            return decodeWire(String(json))
        }

        fun send(message: Wire) {
            outbox.trySend(message)
        }

        fun close() {
            socket.closeQuietly()
            outbox.close()
        }
    }

    private suspend fun acceptLoop(tcp: ServerSocket) {
        coroutineScope {
            while (!tcp.isClosed) {
                val socket =
                    try {
                        tcp.accept()
                    } catch (_: IOException) {
                        if (tcp.isClosed) return@coroutineScope
                        continue
                    }
                // Anyone on the network can open a connection; only so many may sit in the
                // handshake at once.
                if (unproven.get() >= MAX_UNPROVEN_CONNECTIONS) {
                    socket.closeQuietly()
                    continue
                }
                unproven.incrementAndGet()
                launch {
                    var counted = true
                    val conn =
                        runCatching { Conn(socket) }.getOrNull() ?: run {
                            unproven.decrementAndGet()
                            return@launch
                        }
                    conn.onProven = {
                        if (counted) {
                            counted = false
                            unproven.decrementAndGet()
                        }
                    }
                    try {
                        socket.tcpNoDelay = true
                        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                        when (val first = conn.readNow(HANDSHAKE_LINE_MAX)) {
                            is Wire.AuthStart -> answerAuth(conn, first)
                            is Wire.PairStart -> answerPairing(conn, first)
                            else -> Unit
                        }
                    } catch (e: Exception) {
                        log("Incoming connection ended: ${e.message}")
                    } finally {
                        conn.onProven()
                        closed(conn)
                    }
                }
            }
        }
    }

    private fun connectIfPaired(id: String) {
        val running =
            synchronized(lock) {
                if (id !in paired || id in connections || id in dialing) return
                dialing += id
                engineScope
            }
        if (running == null) {
            synchronized(lock) { dialing -= id }
            return
        }
        running.launch {
            try {
                // Both devices hear each other at about the same time. The one with the lower id
                // calls; the other gives it a moment and calls only if nothing arrived (the first
                // one may be unable to: a firewall on this side).
                if (self.id > id) delay(DIAL_YIELD_MS)
                val target = synchronized(lock) { if (id in connections) null else seen[id] } ?: return@launch
                dial(target) { conn -> callAuth(conn, id) }
            } catch (e: Exception) {
                log("Connection to $id ended: ${e.message}")
            } finally {
                synchronized(lock) { dialing -= id }
            }
        }
    }

    private suspend fun dial(
        target: Seen,
        handshake: suspend (Conn) -> Unit,
    ) {
        val socket = Socket()
        var conn: Conn? = null
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target.host, target.tcpPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            conn = Conn(socket)
            handshake(conn)
        } finally {
            socket.closeQuietly()
            conn?.let { closed(it) }
        }
    }

    private suspend fun callAuth(
        conn: Conn,
        id: String,
    ) {
        val key = synchronized(lock) { paired[id]?.key } ?: return
        val nonce = Crypto.randomBytes(16)
        conn.writeNow(Wire.AuthStart(self, Crypto.b64(nonce)))
        when (val reply = conn.readNow(HANDSHAKE_LINE_MAX)) {
            is Wire.Reject -> {
                // The other device no longer knows this one ("Forget" was used there).
                if (reply.reason == REJECT_NOT_PAIRED) dropPairing(id)
                return
            }

            is Wire.AuthReply -> {
                if (reply.device.id != id) return
                conn.peer = reply.device
                conn.cipher = Crypto.frameCipher(Crypto.unb64(key), nonce, Crypto.unb64(reply.nonce), isCaller = true)
            }

            else -> {
                return
            }
        }
        runConnected(conn, readyExchanged = false)
    }

    private suspend fun answerAuth(
        conn: Conn,
        start: Wire.AuthStart,
    ) {
        val key = synchronized(lock) { paired[start.device.id]?.key }
        if (key == null) {
            conn.writeNow(Wire.Reject(REJECT_NOT_PAIRED))
            return
        }
        val nonce = Crypto.randomBytes(16)
        conn.writeNow(Wire.AuthReply(self, Crypto.b64(nonce)))
        conn.peer = start.device
        conn.cipher = Crypto.frameCipher(Crypto.unb64(key), Crypto.unb64(start.nonce), nonce, isCaller = false)
        runConnected(conn, readyExchanged = false)
    }

    /** The life of one encrypted connection. Returns (or throws) when it ends. */
    private suspend fun runConnected(
        conn: Conn,
        readyExchanged: Boolean,
    ) {
        if (!readyExchanged) {
            conn.writeNow(Wire.Ready(self))
            // Opening this frame is the proof: only the holder of the pairing key can have made
            // it, and the nonces of this connection are part of the frame key, so it is not a
            // recording of an earlier one.
            val ready = conn.readNow(HANDSHAKE_LINE_MAX) as? Wire.Ready ?: return
            conn.peer = ready.device.copy(id = conn.peer?.id ?: return)
        }
        val peer = conn.peer ?: return
        conn.onProven()
        register(conn, peer)
        conn.socket.soTimeout = IDLE_TIMEOUT_MS
        coroutineScope {
            val writer =
                launch {
                    try {
                        for (message in conn.outbox) conn.writeNow(message)
                    } catch (_: IOException) {
                        conn.close()
                    }
                }
            val pinger =
                launch {
                    while (true) {
                        delay(PING_INTERVAL_MS)
                        conn.send(Wire.Ping)
                    }
                }
            // Requests run one after another, in the order they were sent.
            val requests = Channel<Wire>(Channel.UNLIMITED)
            val worker = launch { for (request in requests) answerRequest(conn, peer, request) }
            try {
                while (true) {
                    when (val message = conn.readNow(LINE_MAX)) {
                        is Wire.State -> {
                            _players.update {
                                it + (peer.id to RemotePlayer(message.player, it[peer.id]?.queue, System.nanoTime()))
                            }
                        }

                        is Wire.Queue -> {
                            _players.update {
                                val before = it[peer.id]
                                it + (peer.id to RemotePlayer(before?.state ?: PlayerState(), message.queue, before?.receivedAtNanos ?: System.nanoTime()))
                            }
                        }

                        is Wire.Cmd, is Wire.Transfer, is Wire.RequestTransfer -> {
                            requests.trySend(message)
                        }

                        is Wire.Ack -> {
                            pending.remove(message.id)?.answer?.complete(message)
                        }

                        Wire.Ping -> {
                            conn.send(Wire.Pong)
                        }

                        Wire.Bye -> {
                            break
                        }

                        Wire.Unpair -> {
                            dropPairing(peer.id)
                            break
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            } finally {
                writer.cancel()
                pinger.cancel()
                worker.cancel()
                requests.close()
            }
        }
    }

    private suspend fun answerRequest(
        conn: Conn,
        peer: DeviceInfo,
        request: Wire,
    ) {
        val id: Long
        val error: String?
        when (request) {
            is Wire.Cmd -> {
                id = request.id
                error = guarded { port.onCommand(peer, request.command) }
            }

            is Wire.Transfer -> {
                id = request.id
                error = guarded { port.onTransfer(peer, request.player, request.queue, request.play) }
            }

            is Wire.RequestTransfer -> {
                id = request.id
                error = guarded { port.onTransferRequest(peer) }
            }

            else -> {
                return
            }
        }
        conn.send(Wire.Ack(id, error == null, error))
    }

    private suspend fun guarded(block: suspend () -> String?): String? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    private fun register(
        conn: Conn,
        peer: DeviceInfo,
    ) {
        val replaced =
            synchronized(lock) {
                val old = connections.put(peer.id, conn)
                paired[peer.id]?.let { known ->
                    val updated = known.copy(name = peer.name, type = peer.type, lastHost = conn.host ?: known.lastHost)
                    if (updated != known) {
                        paired[peer.id] = updated
                        savePaired()
                    }
                }
                old
            }
        // Both called at once: the newer connection is kept on both sides.
        if (replaced != null && replaced !== conn) replaced.close()
        log("Connected to ${peer.name}")
        localQueue?.let { conn.send(Wire.Queue(it)) }
        localState?.let { conn.send(Wire.State(it)) }
        refreshDevices()
    }

    private fun closed(conn: Conn) {
        conn.close()
        val peerId = conn.peer?.id
        val wasCurrent =
            synchronized(lock) {
                if (peerId != null && connections[peerId] === conn) {
                    connections.remove(peerId)
                    true
                } else {
                    false
                }
            }
        if (wasCurrent && peerId != null) {
            pending.entries.removeAll { (_, waiting) ->
                (waiting.peerId == peerId).also { if (it) waiting.answer.complete(Wire.Ack(0, false, ERROR_NOT_CONNECTED)) }
            }
            log("Disconnected from ${conn.peer?.name}")
            refreshDevices()
        }
    }

    // ------------------------------------------------------------------ pairing

    /** Starts pairing with a device from [devices]. The code then appears in [pairing]. */
    fun startPairing(deviceId: String) {
        val (running, target) =
            synchronized(lock) {
                if (pairingSession != null || deviceId in dialing) return
                val target = seen[deviceId] ?: return
                val running = engineScope ?: return
                dialing += deviceId
                running to target
            }
        running.launch {
            try {
                dial(target) { conn -> callPairing(conn) }
            } catch (e: Exception) {
                log("Pairing ended: ${e.message}")
            } finally {
                synchronized(lock) { dialing -= deviceId }
            }
        }
    }

    private suspend fun callPairing(conn: Conn) {
        val keys = Crypto.newKeyPair()
        val publicKey = keys.public.encoded
        val nonce = Crypto.randomBytes(16)
        conn.writeNow(Wire.PairStart(self, Crypto.b64(Crypto.commitment(publicKey, nonce))))
        val reply = conn.readNow(HANDSHAKE_LINE_MAX) as? Wire.PairKey ?: return
        conn.writeNow(Wire.PairReveal(Crypto.b64(publicKey), Crypto.b64(nonce)))
        val peerKey = Crypto.unb64(reply.pub)
        val peerNonce = Crypto.unb64(reply.nonce)
        val shared = Crypto.ecdh(keys, Crypto.decodePublicKey(peerKey))
        val transcript = Crypto.transcript(publicKey, nonce, peerKey, peerNonce)
        val key = Crypto.pairingKey(shared, transcript)
        conn.peer = reply.device
        conn.cipher = Crypto.frameCipher(key, nonce, peerNonce, isCaller = true)
        runPairing(PairingSession(conn, reply.device, key, Crypto.pairingCode(shared, transcript)))
    }

    private suspend fun answerPairing(
        conn: Conn,
        start: Wire.PairStart,
    ) {
        if (synchronized(lock) { pairingSession != null } || start.device.id == self.id || System.nanoTime() < pairingRefusedUntilNanos) {
            conn.writeNow(Wire.Reject(REJECT_BUSY))
            return
        }
        val keys = Crypto.newKeyPair()
        val publicKey = keys.public.encoded
        val nonce = Crypto.randomBytes(16)
        conn.writeNow(Wire.PairKey(self, Crypto.b64(publicKey), Crypto.b64(nonce)))
        val reveal = conn.readNow(HANDSHAKE_LINE_MAX) as? Wire.PairReveal ?: return
        val peerKey = Crypto.unb64(reveal.pub)
        val peerNonce = Crypto.unb64(reveal.nonce)
        // The caller fixed its key before it saw ours; a different one now means tampering.
        if (!MessageDigest.isEqual(Crypto.commitment(peerKey, peerNonce), Crypto.unb64(start.commit))) return
        val shared = Crypto.ecdh(keys, Crypto.decodePublicKey(peerKey))
        val transcript = Crypto.transcript(peerKey, peerNonce, publicKey, nonce)
        val key = Crypto.pairingKey(shared, transcript)
        conn.peer = start.device
        conn.cipher = Crypto.frameCipher(key, peerNonce, nonce, isCaller = false)
        runPairing(PairingSession(conn, start.device, key, Crypto.pairingCode(shared, transcript)))
    }

    private suspend fun runPairing(session: PairingSession) {
        synchronized(lock) {
            if (pairingSession != null) return
            pairingSession = session
        }
        try {
            _pairing.value = PairingPrompt(session.peer.id, session.peer.name, session.code, confirmedHere = false)
            // Nothing is stored and nothing else is accepted until both people confirmed.
            session.conn.socket.soTimeout = PAIRING_TIMEOUT_MS
            while (true) {
                when (session.conn.readNow(HANDSHAKE_LINE_MAX)) {
                    Wire.PairConfirm -> {
                        synchronized(session) { session.there = true }
                        completeIfBothConfirmed(session)
                    }

                    is Wire.Ready -> {
                        if (!synchronized(session) { session.done }) return
                        break
                    }

                    else -> {
                        return
                    }
                }
            }
        } finally {
            synchronized(lock) { if (pairingSession === session) pairingSession = null }
            _pairing.value = null
        }
        runConnected(session.conn, readyExchanged = true)
    }

    private fun completeIfBothConfirmed(session: PairingSession) {
        synchronized(session) {
            if (!session.here || !session.there || session.done) return
            session.done = true
            synchronized(lock) {
                paired[session.peer.id] =
                    PairedPeer(session.peer.id, session.peer.name, session.peer.type, Crypto.b64(session.key), session.conn.host)
                savePaired()
            }
            session.conn.writeNow(Wire.Ready(self))
        }
    }

    /** The code on both screens is the same. */
    fun confirmPairing() {
        val session = synchronized(lock) { pairingSession } ?: return
        val running = synchronized(lock) { engineScope } ?: return
        _pairing.update { it?.copy(confirmedHere = true) }
        running.launch {
            try {
                val first = synchronized(session) { !session.here.also { session.here = true } }
                if (first) session.conn.writeNow(Wire.PairConfirm)
                completeIfBothConfirmed(session)
            } catch (_: IOException) {
                session.conn.close()
            }
        }
    }

    fun cancelPairing() {
        val session = synchronized(lock) { pairingSession } ?: return
        _pairing.value = null
        // Declined: the same device (or anyone else) cannot put the dialog straight back up.
        pairingRefusedUntilNanos = System.nanoTime() + PAIRING_COOLDOWN_NANOS
        val running = synchronized(lock) { engineScope }
        if (running == null) {
            session.conn.close()
            return
        }
        running.launch {
            runCatching { session.conn.writeNow(Wire.PairCancel) }
            session.conn.close()
        }
    }

    /** Removes a paired device here, and tells it to do the same. */
    fun forget(deviceId: String) {
        val conn = synchronized(lock) { connections[deviceId] }
        if (conn != null) {
            val running = synchronized(lock) { engineScope }
            running?.launch {
                runCatching { conn.writeNow(Wire.Unpair) }
                conn.close()
            } ?: conn.close()
        }
        dropPairing(deviceId)
    }

    private fun dropPairing(id: String) {
        val conn =
            synchronized(lock) {
                if (paired.remove(id) != null) savePaired()
                connections[id]
            }
        _players.update { it - id }
        refreshDevices()
        // Left open here when "Forget" is still telling the other side; it closes it afterwards.
        if (conn != null && synchronized(lock) { engineScope } == null) conn.close()
    }

    private fun savePaired() {
        val snapshot = paired.values.toList()
        runCatching { store.save(snapshot) }
    }

    // ------------------------------------------------------------------ player traffic

    /** Tells every connected device what this one is playing. */
    fun publishState(state: PlayerState) {
        localState = state
        synchronized(lock) { connections.values.toList() }.forEach { it.send(Wire.State(state)) }
    }

    fun publishQueue(queue: QueueState) {
        localQueue = queue
        synchronized(lock) { connections.values.toList() }.forEach { it.send(Wire.Queue(queue)) }
    }

    /** Null when the device ran the command, otherwise what went wrong. */
    suspend fun send(
        deviceId: String,
        command: Command,
        timeoutMs: Long = 8_000,
    ): String? = request(deviceId, timeoutMs) { Wire.Cmd(it, command) }

    /** Hands playback to [deviceId]. Returns once it has taken over. */
    suspend fun transfer(
        deviceId: String,
        player: PlayerState,
        queue: QueueState,
        play: Boolean,
        timeoutMs: Long = 30_000,
    ): String? = request(deviceId, timeoutMs) { Wire.Transfer(it, player, queue, play) }

    /** Asks [deviceId] to hand its playback to this device. */
    suspend fun requestTransfer(
        deviceId: String,
        timeoutMs: Long = 40_000,
    ): String? = request(deviceId, timeoutMs) { Wire.RequestTransfer(it) }

    private suspend fun request(
        deviceId: String,
        timeoutMs: Long,
        build: (Long) -> Wire,
    ): String? {
        val conn = synchronized(lock) { connections[deviceId] } ?: return ERROR_NOT_CONNECTED
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<Wire.Ack>()
        pending[id] = Pending(deviceId, answer)
        conn.send(build(id))
        val ack = withTimeoutOrNull(timeoutMs) { answer.await() }
        pending.remove(id)
        return when {
            ack == null -> ERROR_TIMEOUT
            ack.ok -> null
            else -> ack.error ?: "error"
        }
    }

    /** Suspends until a connection to [deviceId] is open, or [timeoutMs] passed. */
    suspend fun awaitConnected(
        deviceId: String,
        timeoutMs: Long,
    ): Boolean = withTimeoutOrNull(timeoutMs) { devices.first { list -> list.any { it.id == deviceId && it.connected } } } != null

    // ------------------------------------------------------------------ devices list

    private fun refreshDevices() {
        val list =
            synchronized(lock) {
                val ids = LinkedHashSet<String>()
                ids += paired.keys
                if (engineScope != null) {
                    ids += connections.keys
                    ids += seen.keys
                }
                ids.mapNotNull { id ->
                    val known = paired[id]
                    val heard = seen[id]
                    val conn = connections[id]
                    val info = conn?.peer ?: heard?.device
                    PeerDevice(
                        id = id,
                        name = info?.name ?: known?.name ?: return@mapNotNull null,
                        type = info?.type ?: known?.type ?: DEVICE_DESKTOP,
                        paired = known != null,
                        connected = conn != null,
                        visible = heard != null,
                        host = conn?.host ?: heard?.host ?: known?.lastHost,
                    )
                }
            }
        _devices.value =
            list.sortedWith(
                compareByDescending<PeerDevice> { it.connected }
                    .thenByDescending { it.paired }
                    .thenBy { it.name.lowercase() },
            )
    }

    private companion object {
        const val REJECT_NOT_PAIRED = "not_paired"
        const val REJECT_BUSY = "busy"

        /** One line, without its newline. Null at a clean end of the stream. */
        fun readLine(
            input: InputStream,
            maxBytes: Int,
        ): ByteArray? {
            val line = ByteArrayOutputStream(256)
            while (true) {
                val byte = input.read()
                when {
                    byte < 0 -> return if (line.size() == 0) null else throw EOFException()
                    byte == '\n'.code -> return line.toByteArray()
                    line.size() >= maxBytes -> throw IOException("Line longer than $maxBytes bytes")
                    else -> line.write(byte)
                }
            }
        }

        fun Socket.closeQuietly() {
            runCatching { close() }
        }

        fun ServerSocket.closeQuietly() {
            runCatching { close() }
        }
    }
}
