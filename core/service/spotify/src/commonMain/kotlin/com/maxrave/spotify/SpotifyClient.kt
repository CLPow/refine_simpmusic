package com.maxrave.spotify

import com.maxrave.ktorext.curl.CurlLogger
import com.maxrave.ktorext.encoding.brotli
import com.maxrave.ktorext.getEngine
import com.maxrave.spotify.model.body.CanvasBody
import com.maxrave.spotify.model.body.SpotifyClientBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.userAgent
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.random.Random

class SpotifyClient {
    private var spotifyClient = createSpotifyClient()
    private var jsonClient =
        createSpotifyClient(
            onlyJson = true,
        )

    var proxy: ProxyConfig? = null
        set(value) {
            field = value
            spotifyClient.close()
            spotifyClient = createSpotifyClient()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createSpotifyClient(onlyJson: Boolean = false) =
        HttpClient(getEngine()) {
            followRedirects = true
            expectSuccess = false
            install(HttpCache)
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.ALL
            }
            install(CurlLogger) {
                logger = { Logger.DEFAULT.log(it) }
            }
            install(HttpSend) {
                maxSendCount = 100
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                if (!onlyJson) {
                    register(
                        ContentType.Text.Plain,
                        KotlinxSerializationConverter(
                            Json {
                                prettyPrint = true
                                isLenient = true
                                ignoreUnknownKeys = true
                                explicitNulls = false
                                encodeDefaults = true
                            },
                        ),
                    )
                    protobuf(
                        ProtoBuf {
                            encodeDefaults = true
                        },
                    )
                }
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }
            defaultRequest {
                url("https://api.spotify.com")
            }
            if (proxy != null) {
                engine {
                    proxy = this@SpotifyClient.proxy
                }
            }
        }

    /**
     * Thanks to @Thereallo1026 and @misiektoja for providing the latest TOTP secret.
     *
     * Body is integrity-checked against a pinned SHA-256 before it is returned. The dictionary
     * rotates with Spotify client releases, so the pin lives next to the URL and must be
     * refreshed when the upstream file changes — a silent accept of a tampered secretDict
     * would mint attacker-chosen TOTP material for the signed-in account.
     *
     * Returns the verified JSON text (not an HttpResponse) because the body is already
     * consumed for the checksum and a second `body<>()` read would fail.
     */
    suspend fun getSpotifyLastestTotpSecretJson(): String {
        val response =
            spotifyClient.get(TOTP_SECRET_DICT_URL) {
                userAgent(USER_AGENT)
                contentType(ContentType.Application.Json)
            }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Spotify TOTP secretDict HTTP ${response.status.value}")
        }
        val body = response.bodyAsText()
        val actual = body.sha256Hex()
        if (!actual.equals(TOTP_SECRET_DICT_SHA256, ignoreCase = true)) {
            throw IllegalStateException(
                "Spotify TOTP secretDict checksum mismatch: expected $TOTP_SECRET_DICT_SHA256, got $actual",
            )
        }
        return body
    }

    private fun String.sha256Hex(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun getSpotifyServerTime(spdc: String) =
        spotifyClient.get("https://open.spotify.com/api/server-time") {
            userAgent(USER_AGENT)
            header(
                "Cookie",
                "sp_dc=$spdc",
            )
            header("App-platform", "WebPlayer")
            header("Spotify-App-Version", "1.2.61.20.g3b4cd5b2")
            header("Accept", "application/json")
            header("Origin", "https://open.spotify.com")
            header("Referer", "https://open.spotify.com/")
        }

    suspend fun getSpotifyAccessToken(
        spdc: String,
        otpValue: String,
        reason: String = "init",
        sTime: String,
        cTime: String,
        totpVersion: Int,
    ) = spotifyClient.get("https://open.spotify.com/api/token") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header(
            "Cookie",
            "sp_dc=$spdc",
        )
        parameter("reason", reason)
        parameter("productType", "mobile-web-player")
        parameter("totp", otpValue)
        parameter("totpServer", otpValue)
        parameter("totpVer", totpVersion)
        header("Cookie", "sp_dc=$spdc")
        header("Accept", "application/json")
        header("Origin", "https://open.spotify.com")
        header("Referer", "https://open.spotify.com/")
    }

    suspend fun getSpotifyLyricsToken(spdc: String) =
        spotifyClient.get("https://open.spotify.com/get_access_token?reason=transport&productType=web_player") {
            userAgent(USER_AGENT)
            contentType(ContentType.Application.Json)
            header("Cookie", "sp_dc=$spdc")
        }

    suspend fun getSpotifyLyrics(
        token: String,
        clientToken: String,
        trackId: String,
    ) = spotifyClient.get("https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId?format=json&vocalRemoval=false&market=from_token") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("Client-Token", clientToken)
        header("App-platform", "WebPlayer")
    }

    suspend fun searchSpotifyTrack(
        q: String,
        authToken: String,
        clientToken: String,
    ) = spotifyClient.get("https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchTracks") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $authToken")
        header("Client-Token", clientToken)
        header(
            HttpHeaders
                .AcceptEncoding,
            "gzip, deflate, br",
        )
        val variable =
            "{\"searchTerm\":\"${q}\",\"offset\":0,\"limit\":3,\"numberOfTopResults\":3,\"includeAudiobooks\":true,\"includePreReleases\":false}"
        val sha = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        parameter(
            "variables",
            variable,
        )
        parameter(
            "extensions",
            "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"${sha}\"}}",
        )
    }
    // {"searchTerm":"trình+hieuthuhai","offset":0,"limit":20,"numberOfTopResults":20,"includeAudiobooks":true,"includePreReleases":false}
    // {"persistedQuery":{"version":1,"sha256Hash":"e4ed1f91a2cc5415befedb85acf8671dc1a4bf3ca1a5b945a6386101a22e28a6"}}

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = spotifyClient.post("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases") {
        headers {
            append(HttpHeaders.Accept, "application/protobuf")
            append(HttpHeaders.ContentType, "application/protobuf")
            append(
                HttpHeaders
                    .AcceptEncoding,
                "gzip, deflate, br",
            )
            append(HttpHeaders.Authorization, "Bearer $token")
            append("Client-Token", clientToken)
            append(HttpHeaders.UserAgent, "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)")
        }
        setBody(
            CanvasBody(
                tracks =
                    listOf(
                        CanvasBody.Track(
                            track_uri = "spotify:track:$trackId",
                        ),
                    ),
            ),
        )
    }

    suspend fun getSpotifyClientToken() =
        jsonClient.post("https://clienttoken.spotify.com/v1/clienttoken") {
            headers {
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0",
                )
            }
            setBody(
                SpotifyClientBody(
                    client_data = SpotifyClientBody.ClientData(),
                ),
            )
        }

    /**
     * The public embed page for a playlist, as raw HTML.
     *
     * Deliberately unauthenticated. Spotify refuses this app's web-player token on its public Web
     * API — with a 429 and a 24-hour Retry-After, which reads as a rate limit and is really "you
     * are not an approved client" — so the library endpoints are unreachable with the credential
     * we hold. The embed needs no credential at all: it answers 200 to anyone and carries the
     * playlist name, cover art, and up to 100 tracks with titles, artists and durations.
     */
    suspend fun getPlaylistEmbed(playlistId: String) =
        spotifyClient.get("https://open.spotify.com/embed/playlist/$playlistId") {
            userAgent(USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            header(HttpHeaders.AcceptLanguage, "en")
        }

    /**
     * A persisted-query call against the endpoint Spotify's own web player uses.
     *
     * The public Web API refuses this app's token outright, but this host accepts it — it is the
     * same host, token and header set as the track search that has always worked here. Queries are
     * addressed by operation name plus a SHA-256 hash of the query text rather than sent as text,
     * so [sha256Hash] is pinned to a web-player release and has to be refreshed when Spotify ships
     * a new one.
     */
    suspend fun pathfinderQuery(
        operationName: String,
        sha256Hash: String,
        variables: String,
        authToken: String,
        clientToken: String,
    ) = spotifyClient.get("https://api-partner.spotify.com/pathfinder/v1/query") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $authToken")
        header("Client-Token", clientToken)
        header("App-platform", "WebPlayer")
        header(HttpHeaders.Accept, "application/json")
        header(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
        parameter("operationName", operationName)
        parameter("variables", variables)
        parameter("extensions", """{"persistedQuery":{"version":1,"sha256Hash":"$sha256Hash"}}""")
    }

    fun getRandomUserAgent(): String {
        val macOSVersion = "${Random.nextInt(11, 15)}_${Random.nextInt(4, 9)}"
        val webKitVersion = "${Random.nextInt(530, 537)}.${Random.nextInt(30, 37)}"
        val chromeVersion = "${Random.nextInt(80, 105)}.0.${Random.nextInt(3000, 4500)}.${Random.nextInt(60, 125)}"
        val safariVersion = "${Random.nextInt(530, 537)}.${Random.nextInt(30, 36)}"

        return "Mozilla/5.0 (Macintosh; Intel Mac OS X $macOSVersion) AppleWebKit/$webKitVersion (KHTML, like Gecko) Chrome/$chromeVersion Safari/$safariVersion"
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"

        const val TOTP_SECRET_DICT_URL =
            "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
        // Refresh this pin when upstream rotates the dictionary (new Spotify client versions).
        const val TOTP_SECRET_DICT_SHA256 =
            "c306e1e21d6afe35c20bd6cf2eee098df547c6f97845c28813e3d2a991895942"
    }
}