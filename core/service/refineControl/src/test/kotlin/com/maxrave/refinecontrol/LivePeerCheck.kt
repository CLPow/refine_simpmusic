package com.maxrave.refinecontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePeerCheck {
    private fun trackJson(
        id: String,
        title: String,
        seconds: Int,
    ) = """{"album":null,"artists":[{"id":null,"name":"Test"}],"duration":null,"durationSeconds":$seconds,"isAvailable":true,""" +
        """"isExplicit":false,"likeStatus":null,"thumbnails":null,"title":"$title","videoId":"$id","videoType":null,""" +
        """"category":null,"feedbackTokens":null,"resultType":null}"""

    @Test
    fun driveARunningApp() {
        val appId = System.getenv("REFINE_LIVE_APP_ID") ?: return
        val key = System.getenv("REFINE_LIVE_KEY") ?: return
        val address = System.getenv("REFINE_LIVE_ADDRESS") ?: return
        val tracks = listOf(trackJson("dQw4w9WgXcQ", "One", 213), trackJson("fJ9rUzIMcZQ", "Two", 359), trackJson("kJQP7kiw5Fk", "Three", 282))
        val queue = QueueState(rev = 1, tracksJson = tracks.joinToString(",", "[", "]"), playlistName = "Live check", playlistType = "PLAYLIST")

        val store =
            object : PairStore {
                override fun load() = listOf(PairedPeer(appId, "App", DEVICE_DESKTOP, key))

                override fun save(peers: List<PairedPeer>) = Unit
            }
        var received: Triple<PlayerState, QueueState, Boolean>? = null
        val port =
            object : LocalPlayerPort {
                override suspend fun onCommand(
                    from: DeviceInfo,
                    command: Command,
                ): String? = null

                override suspend fun onTransfer(
                    from: DeviceInfo,
                    player: PlayerState,
                    queue: QueueState,
                    play: Boolean,
                ): String? {
                    received = Triple(player, queue, play)
                    return null
                }

                override suspend fun onTransferRequest(from: DeviceInfo): String? = null
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine =
            ControlEngine(scope, DeviceInfo("0-live-tester", "Tester", DEVICE_PHONE), store, port, DatagramSocket(0).use { it.localPort }) {
                println("tester: $it")
            }

        fun state() = engine.players.value[appId]?.state

        suspend fun until(
            what: String,
            seconds: Int = 40,
            condition: () -> Boolean,
        ) {
            try {
                withTimeout(seconds * 1000L) { while (!condition()) delay(100) }
                println("OK   $what   [${state()?.let { "${it.title} @${it.positionMs} playing=${it.isPlaying} loading=${it.loading} index=${it.index}" }}]")
            } catch (e: Exception) {
                throw AssertionError("Timed out waiting for: $what; last state ${state()}", e)
            }
        }

        runBlocking {
            try {
                engine.start()
                withTimeout(30_000) { while (!engine.connectByAddress(address)) delay(300) }
                assertTrue(engine.awaitConnected(appId, 20_000), "connected to the app")
                println("OK   connected, app says: ${state()}")

                assertNull(engine.send(appId, Command.SetVolume(0f)))
                until("the app reports volume 0") { state()?.volume == 0f }

                assertNull(engine.send(appId, Command.PlayQueue(queue, tracks[0], "PLAYLIST_CLICK", 0)))
                until("the first song really plays", 60) { state()?.let { it.trackId == "dQw4w9WgXcQ" && it.isPlaying && it.positionMs > 1500 } == true }
                until("the app's queue has the three songs") { engine.players.value[appId]?.queue?.tracksJson?.let { "kJQP7kiw5Fk" in it && "fJ9rUzIMcZQ" in it } == true }
                assertEquals(0, state()?.index)
                assertTrue((state()?.durationMs ?: 0) > 200_000, "duration known: ${state()?.durationMs}")

                assertNull(engine.send(appId, Command.SeekTo(120_000)))
                until("the seek shows up") { abs((state()?.positionMs ?: 0) - 120_000) < 6_000 }

                assertNull(engine.send(appId, Command.Pause))
                until("paused") { state()?.isPlaying == false }
                val pausedAt = state()!!.positionMs
                delay(2500)
                assertTrue(abs(state()!!.positionMs - pausedAt) < 500, "position holds while paused")
                assertNull(engine.send(appId, Command.Play))
                until("playing again") { state()?.isPlaying == true }

                assertNull(engine.send(appId, Command.Next))
                until("next song", 60) { state()?.let { it.trackId == "fJ9rUzIMcZQ" && it.index == 1 } == true }

                assertNull(engine.send(appId, Command.PlayIndex(0)))
                until("a tap on the first song in the queue", 60) { state()?.let { it.trackId == "dQw4w9WgXcQ" && it.index == 0 } == true }

                val handed = PlayerState(trackId = "kJQP7kiw5Fk", index = 2, positionMs = 90_000, isPlaying = true, repeat = 2)
                val started = System.nanoTime()
                assertNull(engine.transfer(appId, handed, queue.copy(rev = 2, playlistId = "PLtest", autoplayStartIndex = 3), play = true))
                println("OK   transfer answered after ${(System.nanoTime() - started) / 1_000_000} ms")
                until("the app plays the handed song from the handed position") {
                    state()?.let { it.trackId == "kJQP7kiw5Fk" && it.index == 2 && it.isPlaying && it.positionMs in 90_000L..110_000L && it.repeat == 2 } == true
                }
                val appQueue = engine.players.value[appId]?.queue
                assertEquals("PLtest", appQueue?.playlistId)
                assertEquals(3, appQueue?.autoplayStartIndex)

                assertNull(engine.requestTransfer(appId))
                val back = received ?: error("the app did not hand anything over")
                println("OK   handed back: ${back.first.title} @${back.first.positionMs} play=${back.third} index=${back.first.index}")
                assertEquals("kJQP7kiw5Fk", back.first.trackId)
                assertEquals(2, back.first.index)
                assertTrue(back.third)
                assertTrue(back.first.positionMs in 90_000L..130_000L)
                assertTrue("dQw4w9WgXcQ" in back.second.tracksJson)
                until("the app stopped after handing over") { state()?.isPlaying == false }

                assertNull(engine.transfer(appId, PlayerState(index = 1, positionMs = 30_000), queue.copy(rev = 3), play = false))
                until("paused transfer lands on the second song at 0:30") {
                    state()?.let { it.trackId == "fJ9rUzIMcZQ" && it.index == 1 && !it.isPlaying && abs(it.positionMs - 30_000) < 1500 } == true
                }
            } finally {
                runCatching { engine.send(appId, Command.Pause, 3_000) }
                engine.stop()
                scope.cancel()
            }
        }
    }
}
