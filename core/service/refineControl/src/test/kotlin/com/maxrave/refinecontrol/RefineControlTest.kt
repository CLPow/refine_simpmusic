package com.maxrave.refinecontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefineControlTest {
    @Test
    fun messagesSurviveTheWire() {
        val messages =
            listOf(
                Wire.PairStart(DeviceInfo("a", "Phone", DEVICE_PHONE), "commit"),
                Wire.Ready(DeviceInfo("b", "Desk", DEVICE_DESKTOP)),
                Wire.State(PlayerState(trackId = "abc", title = "Song", positionMs = 1234, isPlaying = true, repeat = 2)),
                Wire.Queue(QueueState(rev = 3, tracksJson = "[{\"x\":1}]", playlistId = "PL1", autoplayStartIndex = 4)),
                Wire.Cmd(7, Command.SeekTo(90_000)),
                Wire.Cmd(8, Command.Play),
                Wire.Cmd(9, Command.PlayQueue(QueueState(rev = 1), "{}", "SONG_CLICK", 2)),
                Wire.Transfer(10, PlayerState(index = 1), QueueState(), play = true),
                Wire.Ack(7, false, "nope"),
                Wire.Ping,
            )
        for (message in messages) {
            val line = message.encode()
            assertFalse('\n' in line)
            assertEquals(message, decodeWire(line))
        }
    }

    @Test
    fun bothSidesDeriveTheSameCodeAndKey() {
        val caller = Crypto.newKeyPair()
        val answerer = Crypto.newKeyPair()
        val callerNonce = Crypto.randomBytes(16)
        val answererNonce = Crypto.randomBytes(16)
        val transcript = Crypto.transcript(caller.public.encoded, callerNonce, answerer.public.encoded, answererNonce)
        val sharedA = Crypto.ecdh(caller, Crypto.decodePublicKey(answerer.public.encoded))
        val sharedB = Crypto.ecdh(answerer, Crypto.decodePublicKey(caller.public.encoded))
        assertEquals(Crypto.pairingCode(sharedA, transcript), Crypto.pairingCode(sharedB, transcript))
        assertTrue(Crypto.pairingKey(sharedA, transcript).contentEquals(Crypto.pairingKey(sharedB, transcript)))
        assertEquals(6, Crypto.pairingCode(sharedA, transcript).length)
    }

    @Test
    fun someoneInTheMiddleGetsDifferentCodes() {
        val caller = Crypto.newKeyPair()
        val answerer = Crypto.newKeyPair()
        val attacker = Crypto.newKeyPair()
        val n1 = Crypto.randomBytes(16)
        val n2 = Crypto.randomBytes(16)
        // The caller talks to the attacker, the attacker to the answerer.
        val callerSide = Crypto.transcript(caller.public.encoded, n1, attacker.public.encoded, n2)
        val answererSide = Crypto.transcript(attacker.public.encoded, n1, answerer.public.encoded, n2)
        val keyA = Crypto.pairingKey(Crypto.ecdh(caller, attacker.public), callerSide)
        val keyB = Crypto.pairingKey(Crypto.ecdh(answerer, attacker.public), answererSide)
        assertFalse(keyA.contentEquals(keyB))
        // The commitment pins the caller's key: another key or nonce does not match it.
        val commit = Crypto.commitment(caller.public.encoded, n1)
        assertFalse(commit.contentEquals(Crypto.commitment(attacker.public.encoded, n1)))
        assertFalse(commit.contentEquals(Crypto.commitment(caller.public.encoded, n2)))
    }

    @Test
    fun framesOpenOnlyInOrderWithTheRightKey() {
        val key = Crypto.randomBytes(32)
        val n1 = Crypto.randomBytes(16)
        val n2 = Crypto.randomBytes(16)
        val caller = Crypto.frameCipher(key, n1, n2, isCaller = true)
        val answerer = Crypto.frameCipher(key, n1, n2, isCaller = false)
        val first = caller.seal("one".toByteArray())
        val second = caller.seal("two".toByteArray())
        assertEquals("one", String(answerer.open(first)))
        // A replay of the first frame is not the next frame.
        assertFails { answerer.open(first) }
        assertEquals("two", String(answerer.open(second)))
        assertEquals("back", String(caller.open(answerer.seal("back".toByteArray()))))

        val stranger = Crypto.frameCipher(Crypto.randomBytes(32), n1, n2, isCaller = false)
        assertFails { stranger.open(Crypto.frameCipher(key, n1, n2, isCaller = true).seal("x".toByteArray())) }
        // Same key, another connection (other nonces): a recording does not open either.
        val later = Crypto.frameCipher(key, n1, Crypto.randomBytes(16), isCaller = false)
        assertFails { later.open(Crypto.frameCipher(key, n1, n2, isCaller = true).seal("x".toByteArray())) }
        val changed = Crypto.frameCipher(key, n1, n2, isCaller = true).seal("x".toByteArray()).also { it[0] = (it[0] + 1).toByte() }
        assertFails { Crypto.frameCipher(key, n1, n2, isCaller = false).open(changed) }
    }

    private class MemoryStore : PairStore {
        @Volatile
        var peers: List<PairedPeer> = emptyList()

        override fun load(): List<PairedPeer> = peers

        override fun save(peers: List<PairedPeer>) {
            this.peers = peers
        }
    }

    private class RecordingPort : LocalPlayerPort {
        val commands = CopyOnWriteArrayList<Command>()
        val transfers = CopyOnWriteArrayList<Pair<PlayerState, Boolean>>()
        var transferRequests = 0

        override suspend fun onCommand(
            from: DeviceInfo,
            command: Command,
        ): String? {
            commands += command
            return if (command is Command.Dislike) "refused" else null
        }

        override suspend fun onTransfer(
            from: DeviceInfo,
            player: PlayerState,
            queue: QueueState,
            play: Boolean,
        ): String? {
            transfers += player to play
            return null
        }

        override suspend fun onTransferRequest(from: DeviceInfo): String? {
            transferRequests++
            return null
        }
    }

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    @Test
    fun twoDevicesPairConnectAndTalk() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val portA = freeUdpPort()
            val portB = freeUdpPort()
            val storeA = MemoryStore()
            val storeB = MemoryStore()
            val playerA = RecordingPort()
            val playerB = RecordingPort()
            val a = ControlEngine(scope, DeviceInfo("aaa", "Phone", DEVICE_PHONE), storeA, playerA, portA) { println("A: $it") }
            var b = ControlEngine(scope, DeviceInfo("bbb", "Desk", DEVICE_DESKTOP), storeB, playerB, portB) { println("B: $it") }
            try {
                a.start()
                b.start()
                withTimeout(30_000) {
                    // Wait until both listen, then find each other by address.
                    var found = false
                    while (!found) found = a.connectByAddress("127.0.0.1:$portB")
                    b.devices.first { list -> list.any { it.id == "aaa" } }
                    assertFalse(a.devices.value.first { it.id == "bbb" }.paired)

                    // An unpaired device gets no connection.
                    assertEquals(ERROR_NOT_CONNECTED, a.send("bbb", Command.Play))

                    a.startPairing("bbb")
                    val codeA = a.pairing.first { it != null }!!.code
                    val codeB = b.pairing.first { it != null }!!.code
                    assertEquals(codeA, codeB)
                    assertTrue(storeA.peers.isEmpty() && storeB.peers.isEmpty())
                    a.confirmPairing()
                    assertTrue(storeA.peers.isEmpty())
                    b.confirmPairing()
                    assertTrue(a.awaitConnected("bbb", 10_000))
                    assertTrue(b.awaitConnected("aaa", 10_000))
                    assertNull(a.pairing.value)
                    assertEquals(storeA.peers.single().key, storeB.peers.single().key)

                    // State flows one way, commands the other.
                    b.publishQueue(QueueState(rev = 1, tracksJson = "[1,2]"))
                    b.publishState(PlayerState(trackId = "t1", positionMs = 5000, isPlaying = true, queueRev = 1))
                    val mirrored = a.players.first { it["bbb"]?.state?.trackId == "t1" }.getValue("bbb")
                    assertEquals("[1,2]", mirrored.queue?.tracksJson)
                    assertNull(a.send("bbb", Command.SeekTo(42)))
                    assertEquals(Command.SeekTo(42), playerB.commands.single())
                    assertEquals("refused", a.send("bbb", Command.Dislike))

                    assertNull(a.transfer("bbb", PlayerState(trackId = "t2", index = 3), QueueState(rev = 9), play = true))
                    assertEquals("t2", playerB.transfers.single().first.trackId)
                    assertNull(b.requestTransfer("aaa"))
                    assertEquals(1, playerA.transferRequests)

                    // The desktop restarts: it comes back by itself, with the stored key and the
                    // state it was sent after connecting.
                    b.stop()
                    a.devices.first { list -> list.none { it.id == "bbb" && it.connected } }
                    assertEquals(ERROR_NOT_CONNECTED, a.send("bbb", Command.Play))
                    b = ControlEngine(scope, DeviceInfo("bbb", "Desk 2", DEVICE_DESKTOP), storeB, playerB, portB)
                    b.start()
                    assertTrue(a.awaitConnected("bbb", 20_000))
                    assertEquals("Desk 2", a.devices.first { list -> list.any { it.id == "bbb" && it.name == "Desk 2" } }.first { it.id == "bbb" }.name)
                    assertNull(a.send("bbb", Command.Next))

                    // Forget on one side removes the pairing on both.
                    a.forget("bbb")
                    a.devices.first { list -> list.none { it.id == "bbb" && it.paired } }
                    withTimeout(5_000) { while (storeB.peers.isNotEmpty()) kotlinx.coroutines.delay(50) }
                    assertTrue(storeA.peers.isEmpty())
                }
            } finally {
                a.stop()
                b.stop()
                scope.cancel()
            }
        }

    @Test
    fun cancelledPairingStoresNothing() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val portA = freeUdpPort()
            val portB = freeUdpPort()
            val storeA = MemoryStore()
            val storeB = MemoryStore()
            val a = ControlEngine(scope, DeviceInfo("aaa", "Phone", DEVICE_PHONE), storeA, RecordingPort(), portA)
            val b = ControlEngine(scope, DeviceInfo("bbb", "Desk", DEVICE_DESKTOP), storeB, RecordingPort(), portB)
            try {
                a.start()
                b.start()
                withTimeout(30_000) {
                    var found = false
                    while (!found) found = a.connectByAddress("127.0.0.1:$portB")
                    a.startPairing("bbb")
                    a.pairing.first { it != null }
                    b.pairing.first { it != null }
                    a.confirmPairing()
                    b.cancelPairing()
                    a.pairing.first { it == null }
                    assertTrue(storeA.peers.isEmpty() && storeB.peers.isEmpty())
                    assertNotEquals(true, a.devices.value.firstOrNull { it.id == "bbb" }?.paired)
                }
            } finally {
                a.stop()
                b.stop()
                scope.cancel()
            }
        }
}
