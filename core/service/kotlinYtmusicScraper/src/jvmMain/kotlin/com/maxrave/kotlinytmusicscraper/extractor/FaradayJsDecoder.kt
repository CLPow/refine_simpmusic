package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.cipher.FaradayCipherEngine
import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogLevel
import com.maxrave.kotlinytmusicscraper.cipher.InnerTubeLogger
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.exceptions.ParsingException
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeJavaScriptDecoder
import io.ktor.client.HttpClient
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

private const val TAG = "FaradayJsDecoder"
private const val JS_THREAD_STACK_BYTES = 32L * 1024L * 1024L

internal class FaradayJsDecoder : YoutubeJavaScriptDecoder {
    @Volatile
    var lastOutcome: String = "unused"
        private set

    val lastOutcomeLabel: String
        get() =
            when {
                lastOutcome.startsWith("local") -> "local"
                lastOutcome.startsWith("fallback") -> "pipepipe.dev"
                else -> "cached"
            }

    private val jsThread =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(null, runnable, "QuickJs", JS_THREAD_STACK_BYTES) }
            .asCoroutineDispatcher()

    private val engine by lazy { FaradayCipherEngine(HttpClient(getEngine()), jsThread, bridgedLogger) }

    override fun getPlayerData(videoId: String): YoutubeJavaScriptDecoder.PlayerData {
        val info =
            runBlocking { engine.playerInfo() }
                ?: run {
                    lastOutcome = "fallback:no-player-metadata"
                    throw ParsingException("Faraday: no player metadata")
                }
        return YoutubeJavaScriptDecoder.PlayerData(info.playerId, info.signatureTimestamp)
    }

    override fun decodeBatch(
        playerId: String,
        signatures: List<String>?,
        throttlingParameters: List<String>?,
    ): YoutubeApiDecoder.BatchDecodeResult {
        val wantedSignatures = signatures.orEmpty().distinct()
        val wantedNParameters = throttlingParameters.orEmpty().distinct()
        val result = runBlocking { engine.decode(playerId, wantedSignatures, wantedNParameters) }

        val missing =
            wantedSignatures.count { it !in result.signatures } +
                wantedNParameters.count { it !in result.nParameters }
        val wanted = wantedSignatures.size + wantedNParameters.size
        if (missing > 0) {
            lastOutcome = "fallback:$missing-of-$wanted-unsolved"
            throw ParsingException("Faraday: $missing of $wanted unsolved")
        }
        lastOutcome = "local:sig=${wantedSignatures.size},n=${wantedNParameters.size}"
        Logger.d(TAG, "solved $wanted locally (sig=${wantedSignatures.size} n=${wantedNParameters.size})")
        return YoutubeApiDecoder.BatchDecodeResult(result.signatures, result.nParameters)
    }

    fun invalidate() = runBlocking { engine.invalidate() }

    private val bridgedLogger =
        InnerTubeLogger { event ->
            val message = event.message + event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Logger.d(TAG, message)
                InnerTubeLogLevel.INFO -> Logger.i(TAG, message)
                InnerTubeLogLevel.WARN -> Logger.w(TAG, message)
                InnerTubeLogLevel.ERROR -> Logger.e(TAG, message)
            }
        }
}
