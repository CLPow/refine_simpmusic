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

/**
 * Desktop sign-in the way ytmdesktop does it: Google's real sign-in page in a real, current
 * Chromium. Refine has no browser of its own, so it opens Microsoft Edge (on every Windows 11, kept
 * up to date by Windows) with a fresh, empty profile made only for this, in its own window. The
 * listener signs in there (passkeys and 2-step verification work as in any browser). Refine reads
 * that profile's youtube.com session over Chromium's DevTools protocol, closes the window and
 * deletes the profile. The listener's own Edge and Chrome profiles are never opened or read.
 */
object EdgeSignIn {
    private const val TAG = "EdgeSignIn"

    /** ytmdesktop's sign-in URL: Google's page, then YouTube's sign-in handler, then YouTube Music. */
    private const val SIGN_IN_URL =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=" +
            "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop" +
            "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

    private const val SIGN_IN_TIMEOUT_MS = 15 * 60 * 1000L

    sealed interface Result {
        /** The Cookie header for music.youtube.com, as the Android sign-in hands it over. */
        data class SignedIn(
            val cookie: String,
        ) : Result

        data object NoBrowser : Result

        /** The window was closed, or the time ran out, before the sign-in finished. */
        data object Cancelled : Result

        data class Failed(
            val reason: String,
        ) : Result
    }

    /** Edge first (always there on Windows 11), then Chrome. Null when neither is installed. */
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
                        // Port 0: the browser picks a free port and writes it to DevToolsActivePort.
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

    /** The Cookie header once the window is signed in; null if the listener closes the window first. */
    private suspend fun waitForSignIn(
        cdp: Cdp,
        process: Process,
    ): String? {
        while (process.isAlive) {
            val cookies = runCatching { youTubeCookies(cdp) }.getOrNull()
            if (cookies != null && cookies.isSignedIn()) {
                // Let the last redirects finish writing their cookies.
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

    /** Every cookie YouTube Music is sent: those of .youtube.com and music.youtube.com. */
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

    /** YouTube's own signed-in pair: the API key cookie and the session marker it sets after sign-in. */
    private fun List<BrowserCookie>.isSignedIn(): Boolean =
        any { it.name == "SAPISID" || it.name == "__Secure-3PAPISID" } && any { it.name == "LOGIN_INFO" }

    private fun List<BrowserCookie>.toHeader(): String = distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }

    private suspend fun deleteProfile(profile: File) {
        // The browser keeps files open for a moment after it exits.
        repeat(10) {
            if (!profile.exists() || profile.deleteRecursively()) return
            delay(500)
        }
        Logger.w(TAG, "Could not delete the sign-in profile at ${profile.path}; it is in the temp folder")
    }

    /** The smallest DevTools protocol client: one WebSocket, requests matched to answers by id. */
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
