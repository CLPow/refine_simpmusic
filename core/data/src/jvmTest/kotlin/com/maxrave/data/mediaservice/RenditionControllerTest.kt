package com.maxrave.data.mediaservice

import com.maxrave.data.db.PodcastDao
import com.maxrave.domain.data.entities.RenditionPairEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.Rendition
import com.maxrave.domain.repository.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenditionControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stored = ConcurrentHashMap<String, RenditionPairEntity>()
    private val searched = mutableListOf<String>()
    private var songVersion: Track? = null

    @AfterTest
    fun tearDown() = scope.cancel()

    private inline fun <reified T> fake(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> answer(method.name, args ?: emptyArray())
            }
        } as T

    private fun controller() =
        RenditionControllerImpl(
            songRepository =
                fake { name, args ->
                    check(name == "findSongVersion") { "unexpected $name" }
                    searched += (args[0] as Track).videoId
                    songVersion
                },
            podcastDao =
                fake { name, args ->
                    when (name) {
                        "getRenditionPair" -> stored[args[0] as String]
                        "upsertRenditionPair" -> (args[0] as RenditionPairEntity).let { stored[it.videoId] = it }
                        else -> error("unexpected $name")
                    }
                },
            dataStoreManager =
                fake<DataStoreManager> { name, _ ->
                    when (name) {
                        "getString" -> flowOf(null)
                        "putString" -> Unit
                        else -> error("unexpected $name")
                    }
                },
            scope = scope,
        )

    private fun track(
        id: String,
        type: String,
    ) = Track(
        album = null,
        artists = emptyList(),
        duration = null,
        durationSeconds = 226,
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = null,
        title = id,
        videoId = id,
        videoType = type,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )

    private val video = track("omv", "MUSIC_VIDEO_TYPE_OMV")

    private fun RenditionControllerImpl.playAndResolve(track: Track) =
        runBlocking {
            onNowPlaying(track)
            withTimeout(5_000) { current.first { it?.mediaId == track.videoId && !it.resolving } }
        }

    @Test
    fun pairSavedByTheOldMatcherIsRecheckedSoPreviousPlaysTheRightSong() {
        stored["omv"] = RenditionPairEntity("omv", "instrumental", 1_795_100_000_000L)
        stored["instrumental"] = RenditionPairEntity("instrumental", "omv", 1_795_100_000_000L)
        songVersion = track("song", "MUSIC_VIDEO_TYPE_ATV")

        val controller = controller()
        controller.playAndResolve(video)

        assertEquals(Rendition(audioId = "song", videoId = null), controller.renditionFor("omv"))
        assertEquals(listOf("omv"), searched)
        assertNull(stored.getValue("instrumental").counterpartId)
    }

    @Test
    fun pairSavedByTheCurrentMatcherIsTrustedWithoutSearching() {
        stored["omv"] = RenditionPairEntity("omv", "song", System.currentTimeMillis())

        val controller = controller()
        controller.playAndResolve(video)

        assertEquals(Rendition(audioId = "song", videoId = null), controller.renditionFor("omv"))
        assertEquals(emptyList(), searched)
    }

    @Test
    fun songModePlaysAVideoWithNoSongVersionAsAudioOnly() {
        val controller = controller()
        controller.playAndResolve(video)

        assertEquals(Rendition(audioId = "omv", videoId = null), controller.renditionFor("omv"))
        controller.setVideoMode(true)
        assertEquals(Rendition(audioId = "omv", videoId = "omv"), controller.renditionFor("omv"))
        controller.setVideoMode(false)
        assertEquals(Rendition(audioId = "omv", videoId = null), controller.renditionFor("omv"))
    }
}
