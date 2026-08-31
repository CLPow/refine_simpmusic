package com.maxrave.spotify

import com.maxrave.spotify.auth.SpotifyAuth
import com.maxrave.spotify.model.response.spotify.CanvasResponse
import com.maxrave.spotify.model.response.spotify.ClientTokenResponse
import com.maxrave.spotify.model.response.spotify.PersonalTokenResponse
import com.maxrave.spotify.model.response.spotify.SpotifyLyricsResponse
import com.maxrave.spotify.model.response.spotify.SpotifyPlaylistPage
import com.maxrave.spotify.model.response.spotify.SpotifyTrackPage
import com.maxrave.spotify.model.response.spotify.search.SpotifySearchResponse
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
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

    suspend fun getCurrentUserPlaylists(
        authToken: String,
        clientToken: String,
        offset: Int,
    ) = runCatching {
        spotifyClient
            .getCurrentUserPlaylists(authToken, clientToken, offset)
            .requireSuccess()
            .body<SpotifyPlaylistPage>()
    }

    suspend fun getPlaylistTracks(
        authToken: String,
        clientToken: String,
        playlistId: String,
        offset: Int,
    ) = runCatching {
        spotifyClient
            .getPlaylistTracks(authToken, clientToken, playlistId, offset)
            .requireSuccess()
            .body<SpotifyTrackPage>()
    }

    suspend fun getSavedTracks(
        authToken: String,
        clientToken: String,
        offset: Int,
    ) = runCatching {
        spotifyClient
            .getSavedTracks(authToken, clientToken, offset)
            .requireSuccess()
            .body<SpotifyTrackPage>()
    }

    /**
     * Turns a rejected request into a sentence instead of letting it reach the JSON parser.
     *
     * Spotify answers an expired or under-scoped token with a JSON error object, which decodes into
     * the expected page type about as well as anything else does — the user would otherwise see a
     * serialization message and no hint that they simply need to sign in again.
     */
    private fun HttpResponse.requireSuccess(): HttpResponse =
        also {
            if (!status.isSuccess()) {
                throw IllegalStateException(
                    when (status.value) {
                        401, 403 -> "Spotify rejected the session. Sign out of Spotify in Settings and sign in again."
                        429 ->
                            headers["Retry-After"]?.let { "Spotify is rate limiting this account. Try again in $it seconds." }
                                ?: "Spotify is rate limiting this account. Try again in a few minutes."
                        else -> "Spotify returned ${status.value}"
                    },
                )
            }
        }
}