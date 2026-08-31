package com.maxrave.spotify

import com.maxrave.spotify.auth.SpotifyAuth
import com.maxrave.spotify.model.response.spotify.CanvasResponse
import com.maxrave.spotify.model.response.spotify.ClientTokenResponse
import com.maxrave.spotify.model.response.spotify.PersonalTokenResponse
import com.maxrave.spotify.model.response.spotify.SpotifyLyricsResponse
import com.maxrave.spotify.model.response.spotify.SpotifyEmbedResponse
import com.maxrave.spotify.model.response.spotify.SpotifyPlaylistSlice
import com.maxrave.spotify.model.response.spotify.SpotifyTrackInfo
import com.maxrave.spotify.model.response.spotify.search.SpotifySearchResponse
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http

class Spotify {
    private val spotifyClient = SpotifyClient()
    private val spotifyAuth = SpotifyAuth(spotifyClient)

    /**
     * Remove proxy for client
     */
    fun removeProxy() {
        spotifyClient.proxy = null
    }

    /**
     * Set the proxy for client
     */
    fun setProxy(
        isHttp: Boolean,
        host: String,
        port: Int,
    ) {
        val verifiedHost =
            if (!host.contains("http")) {
                "http://$host"
            } else {
                host
            }
        runCatching {
            if (isHttp) ProxyBuilder.http("$verifiedHost:$port") else ProxyBuilder.socks(verifiedHost, port)
        }.onSuccess {
            spotifyClient.proxy = it
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * Get personal token using the standard method
     */
    suspend fun getPersonalToken(spdc: String) =
        runCatching {
            spotifyClient.getSpotifyLyricsToken(spdc).body<PersonalTokenResponse>()
        }

    /**
     * Get personal token using the more reliable TOTP-based method
     * This should be used when the standard method fails
     */
    suspend fun getPersonalTokenWithTotp(spdc: String) = spotifyAuth.refreshToken(spdc)

    suspend fun getClientToken() =
        runCatching {
            spotifyClient
                .getSpotifyClientToken()
                .body<ClientTokenResponse>()
        }

    suspend fun searchSpotifyTrack(
        query: String,
        authToken: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .searchSpotifyTrack(query, authToken, clientToken)
            .body<SpotifySearchResponse>()
    }

    suspend fun getSpotifyLyrics(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .getSpotifyLyrics(
                token = token,
                clientToken = clientToken,
                trackId,
            ).body<SpotifyLyricsResponse>()
    }

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient.getSpotifyCanvas(trackId, token, clientToken).body<CanvasResponse>()
    }

    /**
     * Reads a playlist from its public embed page.
     *
     * The page ships its state as a `__NEXT_DATA__` script tag, so the JSON is cut out of the HTML
     * rather than requested directly — there is no JSON endpoint for this that does not require a
     * credential Spotify will not give us.
     */
    suspend fun getPlaylistFromEmbed(playlistId: String) =
        runCatching {
            val response = spotifyClient.getPlaylistEmbed(playlistId)
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    when (response.status.value) {
                        404 -> "That playlist could not be found. It may be private — only playlists with a shareable link can be imported."
                        else -> "Spotify returned ${response.status.value}"
                    },
                )
            }
            val html = response.bodyAsText()
            val json =
                NEXT_DATA.find(html)?.groupValues?.get(1)
                    ?: throw IllegalStateException(
                        "Spotify's page format changed and the playlist could not be read.",
                    )
            val pageProps =
                embedJson
                    .decodeFromString<SpotifyEmbedResponse>(json)
                    .props
                    ?.pageProps
            val entity =
                pageProps?.state?.data?.entity
                    ?: throw IllegalStateException(
                    // Spotify serves a 200 carrying an inner 404 for anything it will not show,
                    // which in practice is nearly always a private playlist. It gives the same
                    // answer for one that does not exist, so the message has to cover both.
                        if (pageProps?.status != null) {
                            "Spotify will not share that playlist. If it is private, open it in Spotify, " +
                                "tap the three dots and choose Make public, then paste the link again."
                        } else {
                            "That link did not contain a playlist."
                        },
                    )
            SpotifyPlaylistSlice(
                name = entity.name ?: entity.title,
                coverArt = entity.coverArt?.sources?.lastOrNull()?.url,
                // The embed page states no total, which is precisely why a full page cannot be
                // told apart from a truncated one.
                total = null,
                tracks =
                    entity.trackList.mapNotNull { track ->
                        track.title?.takeIf { it.isNotBlank() }?.let {
                            SpotifyTrackInfo(
                                title = it,
                                artist = track.subtitle.orEmpty(),
                                durationMs = track.duration,
                            )
                        }
                    },
            )
        }

    /**
     * One page of a playlist's tracks, read through the endpoint the web player itself uses.
     *
     * Works on playlists the signed-in user can see, private ones included, and pages properly, so
     * neither of the embed page's limits apply here.
     *
     * The response is walked for track-shaped objects rather than bound to a fixed path. Spotify
     * does not document this schema and reshapes it at will; keying on the one thing that is stable
     * — a GraphQL `__typename` of "Track" — survives the nesting moving around, and several spellings
     * of the duration field are tried because the shape has used more than one.
     */
    suspend fun getPlaylistTracksViaPathfinder(
        playlistUri: String,
        offset: Int,
        limit: Int,
        authToken: String,
        clientToken: String,
    ) = runCatching {
        val response =
            spotifyClient.pathfinderQuery(
                operationName = "fetchPlaylist",
                sha256Hash = FETCH_PLAYLIST_HASH,
                // Field names and defaults taken from the web player's own call site rather than
                // guessed; the two booleans are feature flags it always sends.
                variables =
                    """{"uri":"$playlistUri","offset":$offset,"limit":$limit,""" +
                        """"enableWatchFeedEntrypoint":false,"includeEpisodeContentRatingsV2":false}""",
                authToken = authToken,
                clientToken = clientToken,
            )
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                when (response.status.value) {
                    401, 403 -> "Spotify rejected the session. Sign out of Spotify in Settings and sign in again."
                    429 -> "Spotify is rate limiting this account. Try again in a few minutes."
                    else -> "Spotify returned ${response.status.value}"
                },
            )
        }
        val root = embedJson.parseToJsonElement(response.bodyAsText())
        root.jsonObject["errors"]?.let { errors ->
            throw IllegalStateException(
                errors.firstMessage() ?: "Spotify rejected the playlist request.",
            )
        }
        val playlist = root.findObject { it["__typename"]?.stringOrNull() == "Playlist" }
        SpotifyPlaylistSlice(
            name = playlist?.get("name")?.stringOrNull(),
            coverArt = null,
            total = root.findInt("totalCount"),
            tracks =
                root
                    .allObjects()
                    .filter { it["__typename"]?.stringOrNull() == "Track" }
                    .mapNotNull { it.toTrackInfo() }
                    .toList(),
        )
    }

    private companion object {
        /**
         * Persisted-query hashes, read out of the web player's public JavaScript bundle.
         *
         * Pinned to a web-player release: when Spotify ships a new one these stop matching and the
         * call fails until they are refreshed from the bundle again.
         */
        private const val FETCH_PLAYLIST_HASH =
            "86dde7b9d9356e2369414647cf6950cfed96e778e129cfdfc99aea6c1613b3b0"

        /** The state blob Spotify's embed page carries; `.` must span lines, hence DOT_MATCHES_ALL. */
        private val NEXT_DATA =
            Regex(
                """<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>""",
                RegexOption.DOT_MATCHES_ALL,
            )

        private val embedJson = Json { ignoreUnknownKeys = true }
    }
}