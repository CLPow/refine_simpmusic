package com.maxrave.simpmusic.desktop

import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// The listener's own Edge and Chrome profiles are never opened or read.
object EdgeSignIn {
    private const val TAG = "EdgeSignIn"

    private const val SIGN_IN_URL =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=" +
            "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop" +
            "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

    private const val SIGN_IN_TIMEOUT_MS = 15 * 60 * 1000L

    sealed interface Result {
        data class SignedIn(
            val cookie: String,
        ) : Result

        data object NoBrowser : Result

        data object Cancelled : Result

        data class Failed(
            val reason: String,
        ) : Result
    }

    fun findBrowser(): File? {
        val roots =
            listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
        val relative =
            listOf(
                "Microsoft/Edge/Application/msedge.exe",
                "Google/Chrome/Application/chrome.exe",
            )
        return relative.firstNotNullOfOrNull { rel -> roots.map { File(it, rel) }.firstOrNull { it.isFile } }
    }

    suspend fun signIn(): Result =
        withContext(Dispatchers.IO) {
            val browser = findBrowser() ?: return@withContext Result.NoBrowser
            val profile = Files.createTempDirectory("refine-signin-").toFile()
            var process: Process? = null
            try {
                process =
                    ProcessBuilder(
                        browser.path,
                        "--user-data-dir=${profile.path}",
                        "--remote-debugging-port=0",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--disable-sync",
                        "--new-window",
                        SIGN_IN_URL,
                    ).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                val endpoint =
                    withTimeoutOrNull(20_000) { devToolsEndpoint(profile) }
                        ?: return@withContext Result.Failed("The sign-in window did not start")
                Cdp.connect(endpoint).use { cdp ->
                    val cookie = withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { waitForSignIn(cdp, process) }
                    runCatching { cdp.send("Browser.close") }
                    if (cookie == null) Result.Cancelled else Result.SignedIn(cookie)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Sign-in failed: ${e.message}", e)
                Result.Failed(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                process?.let { p ->
                    if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                }
                deleteProfile(profile)
            }
        }

    private suspend fun waitForSignIn(
        cdp: Cdp,
        process: Process,
    ): String? {
        while (process.isAlive) {
            val cookies = runCatching { youTubeCookies(cdp) }.getOrNull()
            if (cookies != null && cookies.isSignedIn()) {
                delay(1_500)
                return youTubeCookies(cdp).toHeader()
            }
            delay(1_000)
        }
        return null
    }

    private suspend fun devToolsEndpoint(profile: File): String {
        val file = File(profile, "DevToolsActivePort")
        while (true) {
            val lines = runCatching { file.readLines() }.getOrNull()
            if (lines != null && lines.size >= 2 && lines[0].toIntOrNull() != null) {
                return "ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}"
            }
            delay(200)
        }
    }

    private data class BrowserCookie(
        val name: String,
        val value: String,
        val domain: String,
    )

    private suspend fun youTubeCookies(cdp: Cdp): List<BrowserCookie> {
        val result = cdp.send("Storage.getCookies")
        return result["cookies"]!!.jsonArray.map { it.jsonObject }.map {
            BrowserCookie(
                name = it["name"]!!.jsonPrimitive.content,
                value = it["value"]!!.jsonPrimitive.content,
                domain = it["domain"]!!.jsonPrimitive.content,
            )
        }.filter { it.domain == ".youtube.com" || it.domain == "youtube.com" || it.domain.endsWith("music.youtube.com") }
    }

    private fun List<BrowserCookie>.isSignedIn(): Boolean =
        any { it.name == "SAPISID" || it.name == "__Secure-3PAPISID" } && any { it.name == "LOGIN_INFO" }

    private fun List<BrowserCookie>.toHeader(): String = distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }

    private suspend fun deleteProfile(profile: File) {
        repeat(10) {
            if (!profile.exists() || profile.deleteRecursively()) return
            delay(500)
        }
        Logger.w(TAG, "Could not delete the sign-in profile at ${profile.path}; it is in the temp folder")
    }

    private class Cdp private constructor() : AutoCloseable {
        private val json = Json { ignoreUnknownKeys = true }
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private lateinit var socket: WebSocket

        companion object {
            suspend fun connect(endpoint: String): Cdp {
                val cdp = Cdp()
                cdp.socket =
                    HttpClient
                        .newHttpClient()
                        .newWebSocketBuilder()
                        .buildAsync(URI(endpoint), cdp.Listener())
                        .await()
                return cdp
            }
        }

        suspend fun send(method: String): JsonObject {
            val id = nextId.getAndIncrement()
            val answer = CompletableFuture<JsonObject>()
            pending[id] = answer
            socket.sendText("""{"id":$id,"method":"$method"}""", true).await()
            val reply = withTimeoutOrNull(10_000) { answer.await() } ?: error("$method: no answer")
            reply["error"]?.let { error("$method: ${it.jsonObject["message"]?.jsonPrimitive?.contentOrNull}") }
            return reply["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        override fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "") }
            pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }
        }

        private inner class Listener : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onText(
                webSocket: WebSocket,
                data: CharSequence,
                last: Boolean,
            ): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    runCatching {
                        val message = json.parseToJsonElement(text).jsonObject
                        message["id"]?.jsonPrimitive?.int?.let { pending.remove(it)?.complete(message) }
                    }
                }
                webSocket.request(1)
                return null
            }
        }
    }
}
