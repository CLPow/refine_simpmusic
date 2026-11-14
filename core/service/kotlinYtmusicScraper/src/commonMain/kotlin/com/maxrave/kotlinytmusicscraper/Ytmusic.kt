package com.maxrave.kotlinytmusicscraper

import com.maxrave.domain.extension.now
import com.maxrave.kotlinytmusicscraper.extractor.Extractor
import com.maxrave.kotlinytmusicscraper.models.Context
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient.Companion.TVHTML5
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient.Companion.WEB_REMIX
import com.maxrave.kotlinytmusicscraper.models.YouTubeLocale
import com.maxrave.kotlinytmusicscraper.models.body.AccountMenuBody
import com.maxrave.kotlinytmusicscraper.models.body.BrowseBody
import com.maxrave.kotlinytmusicscraper.models.body.CreateCommentBody
import com.maxrave.kotlinytmusicscraper.models.body.CreateCommentReplyBody
import com.maxrave.kotlinytmusicscraper.models.body.CreatePlaylistBody
import com.maxrave.kotlinytmusicscraper.models.body.EditPlaylistBody
import com.maxrave.kotlinytmusicscraper.models.body.FormData
import com.maxrave.kotlinytmusicscraper.models.body.GetQueueBody
import com.maxrave.kotlinytmusicscraper.models.body.GetSearchSuggestionsBody
import com.maxrave.kotlinytmusicscraper.models.body.LikeBody
import com.maxrave.kotlinytmusicscraper.models.body.SubscribeBody
import com.maxrave.kotlinytmusicscraper.models.body.NextBody
import com.maxrave.kotlinytmusicscraper.models.body.PerformCommentActionBody
import com.maxrave.kotlinytmusicscraper.models.body.PlayerBody
import com.maxrave.kotlinytmusicscraper.models.body.SearchBody
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.kotlinytmusicscraper.utils.parseCookieString
import com.maxrave.kotlinytmusicscraper.utils.sha1
import com.maxrave.ktorext.encoding.brotli
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import io.ktor.serialization.kotlinx.xml.xml
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.serialization.XML
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.time.ExperimentalTime

private const val TAG = "YouTubeScraperClient"
private const val DOWNLOAD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val DEFAULT_PARALLEL_DOWNLOADS = 4
private const val CHUNK_SIZE = 1024L * 1024L // 1MB per chunk

class Ytmusic {
    val normalJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    private var httpClient = createClient()
    private var downloadClient: HttpClient? = null

    private fun getDownloadClient(): HttpClient =
        downloadClient ?: HttpClient(getEngine()) {
            expectSuccess = true
            install(HttpRedirect) {
                checkHttpMethod = false
                allowHttpsDowngrade = true
            }
            // Disable logging for download - significantly improves performance
            // Note: Don't install Logging plugin for download client
        }.also { downloadClient = it }

    /**
     * Paces and backs off traffic that carries the user's account. Public so the app can show the
     * user when it is standing down rather than leaving the app looking broken.
     */
    val accountGuard = AccountGuard()

    var cookiePath: Path? = null

    var locale =
        YouTubeLocale(
            gl = getCountry(),
            hl = getLanguage(),
        )
    var visitorData: String? = null
    var dataSyncId: String? = null
    var cookie: String? = null
        set(value) {
            field = value
            cookieMap = if (value == null) emptyMap() else parseCookieString(value)
            extractor.logIn(value)
        }

    var pageId: String? = null

    // Index of the Google account inside the browser session the cookie came from
    // (the `authuser` query param on youtube.com). A brand channel's pageId is only
    // valid together with the authuser that owns it; sending 0 for a channel of the
    // second signed-in account makes YouTube answer as if not logged in.
    var authUser: Int = 0

    private var cookieMap = emptyMap<String, String>()

    var proxy: ProxyConfig? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    private val extractor = Extractor()

    init {
        extractor.init()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() =
        HttpClient(getEngine()) {
            expectSuccess = true
            install(HttpRedirect) {
                checkHttpMethod = false
                allowHttpsDowngrade = true
            }
            install(ContentNegotiation) {
                protobuf()
                json(
                    normalJson,
                )
                xml(
                    format =
                        XML {
                            xmlDeclMode = XmlDeclMode.Charset
                            autoPolymorphic = true
                        },
                    contentType = ContentType.Text.Xml,
                )
            }

            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }

            if (proxy != null) {
                engine {
                    proxy = this@Ytmusic.proxy
                }
            }

            defaultRequest {
                url("https://music.youtube.com/youtubei/v1/")
            }
        }.apply {
            // Every request to YouTube is sent through here, which is the one place a guard can be
            // applied without trusting each caller to remember. ytClient() attaches the Cookie
            // header only when setLogin is true, so its presence is exactly the marker for "this
            // request spends the user's account".
            plugin(HttpSend).intercept { request ->
                val carriesAccount = request.headers.contains(HttpHeaders.Cookie)
                if (carriesAccount) accountGuard.awaitTurn()
                val call = execute(request)
                if (carriesAccount) accountGuard.onResponse(call.response.status.value)
                call
            }
        }

    @OptIn(ExperimentalTime::class)
    private fun HttpRequestBuilder.ytClient(
        client: YouTubeClient,
        setLogin: Boolean = false,
        isUsingReferer: Boolean = true,
        customCookie: String? = null,
        // A request made as no one: without the account selectors as well as without the cookie,
        // since a brand account's Authuser/Pageid on a cookieless request can be refused.
        anonymous: Boolean = false,
    ) {
        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", "${client.xClientName ?: 1}")
            append("X-YouTube-Client-Version", client.clientVersion)
            if (!anonymous) {
                append("X-Goog-Authuser", authUser.toString())
                pageId?.let {
                    append("X-Goog-Pageid", it)
                }
            }
            append("x-origin", "https://music.youtube.com")
            if (client.referer != null && isUsingReferer) {
                append("Referer", client.referer)
            }
            if (setLogin) {
                val cookie = customCookie ?: this@Ytmusic.cookie
                cookie?.let { cookie ->
                    append("Cookie", cookie)
                    if ("SAPISID" !in cookieMap || "__Secure-3PAPISID" !in cookieMap) return@let
                    // SAPISIDHASH is built from Unix time in seconds (it was divided by 1000 again, giving 1970).
                    val currentTime = now().toInstant(TimeZone.currentSystemDefault()).epochSeconds
                    val sapisidCookie = cookieMap["SAPISID"] ?: cookieMap["__Secure-3PAPISID"]
                    val sapisidHash = sha1("$currentTime $sapisidCookie https://music.youtube.com")
                    append("Authorization", "SAPISIDHASH ${currentTime}_$sapisidHash")
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    fun getNewPipePlayer(videoId: String): List<Pair<Int, String>> = extractor.newPipePlayer(videoId)

    fun mergeAudioVideoDownload(filePath: String): DownloadProgress = extractor.mergeAudioVideoDownload(filePath)

    fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ) = extractor.saveAudioWithThumbnail(filePath, track)

    /**
     * @param setLogin attach the signed-in session. Defaults to true, which is what every
     * user-facing search wants. The Spotify import passes false: it fires one search per imported
     * track, and those are not searches the user performed — letting them into the account's search
     * history would feed thousands of queries into YouTube's personalisation.
     */
    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = true,
    ) = httpClient.post("search") {
        ytClient(client, setLogin)
        setBody(
            SearchBody(
                context = client.toContext(locale, visitorData),
                query = query,
                params = params,
            ),
        )
        parameter("continuation", continuation)
        parameter("ctoken", continuation)
    }

    suspend fun returnYouTubeDislike(videoId: String) =
        httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
            contentType(ContentType.Application.Json)
        }

    suspend fun getSimpMusicChart() =
        httpClient.get("https://chart.simpmusic.org/api/playlists") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
        }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        cpn: String?,
        signatureTimestamp: Int? = null,
    ) = httpClient.post("player") {
        ytClient(client, setLogin = true)
        setBody(
            PlayerBody(
                context =
                    client.toContext(locale, visitorData).let {
                        if (client == TVHTML5) {
                            it.copy(
                                thirdParty =
                                    Context.ThirdParty(
                                        embedUrl = "https://www.youtube.com/watch?v=$videoId",
                                    ),
                            )
                        } else {
                            it
                        }
                    },
                videoId = videoId,
                playlistId = playlistId,
                cpn = cpn,
                playbackContext =
                    PlayerBody.PlaybackContext(
                        contentPlaybackContext =
                            PlayerBody.PlaybackContext.ContentPlaybackContext(
                                signatureTimestamp = signatureTimestamp ?: 20073,
                            ),
                    ),
//                serviceIntegrityDimensions =
//                    if (poToken != null) {
//                        PlayerBody.ServiceIntegrityDimensions(
//                            poToken = poToken,
//                        )
//                    } else {
//                        null
//                    },
            ),
        )
    }

    suspend fun getSuggestQuery(query: String) =
        httpClient.get("http://suggestqueries.google.com/complete/search") {
            contentType(ContentType.Application.Json)
            parameter("client", "firefox")
            parameter("ds", "yt")
            parameter("q", query)
        }

    suspend fun getYouTubeCaption(url: String) =
        httpClient.get(url) {
            contentType(ContentType.Text.Xml)
            headers {
                append(HttpHeaders.Accept, "text/xml; charset=UTF-8")
            }
        }

    suspend fun createYouTubePlaylist(
        title: String,
        listVideoId: List<String>?,
    ) = httpClient.post("playlist/create") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            CreatePlaylistBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                title = title,
                videoIds = listVideoId,
            ),
        )
    }

    suspend fun editYouTubePlaylist(
        playlistId: String,
        title: String? = null,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        EditPlaylistBody.Action(
                            action = "ACTION_SET_PLAYLIST_NAME",
                            playlistName = title ?: "",
                        ),
                    ),
            ),
        )
    }

    suspend fun addItemYouTubePlaylist(
        playlistId: String,
        videoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        EditPlaylistBody.Action(
                            playlistName = null,
                            action = "ACTION_ADD_VIDEO",
                            addedVideoId = videoId,
                        ),
                    ),
            ),
        )
    }

    suspend fun removeItemYouTubePlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        EditPlaylistBody.Action(
                            action = "ACTION_REMOVE_VIDEO",
                            removedVideoId = videoId,
                            setVideoId = setVideoId,
                        ),
                    ),
            ),
        )
    }

    /**
     * Move a playlist item before another item.
     * @param playlistId The YouTube playlist ID
     * @param setVideoId The setVideoId of the item to move
     * @param movedSetVideoIdSuccessor The setVideoId of the item that should come AFTER the moved item.
     *        If null, the item is moved to the end of the playlist.
     */
    suspend fun moveItemYouTubePlaylist(
        playlistId: String,
        setVideoId: String,
        movedSetVideoIdSuccessor: String? = null,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        EditPlaylistBody.Action(
                            action = "ACTION_MOVE_VIDEO_BEFORE",
                            setVideoId = setVideoId,
                            movedSetVideoIdSuccessor = movedSetVideoIdSuccessor,
                        ),
                    ),
            ),
        )
    }

    /***
     * SponsorBlock testing
     * @author maxrave-dev
     */

    suspend fun getSkipSegments(videoId: String) =
        httpClient.get("https://sponsor.ajay.app/api/skipSegments/") {
            contentType(ContentType.Application.Json)
            parameter("videoID", videoId)
            parameter("category", "sponsor")
            parameter("category", "selfpromo")
            parameter("category", "interaction")
            parameter("category", "intro")
            parameter("category", "outro")
            parameter("category", "preview")
            parameter("category", "music_offtopic")
            parameter("category", "poi_highlight")
            parameter("category", "filler")
            parameter("service", "YouTube")
        }

    suspend fun playlist(playlistId: String) =
        httpClient.post("browse") {
            ytClient(WEB_REMIX, !cookie.isNullOrEmpty())
            setBody(
                BrowseBody(
                    context =
                        WEB_REMIX.toContext(
                            locale,
                            visitorData,
                        ),
                    browseId = playlistId,
                    params = "wAEB",
                ),
            )
            parameter("alt", "json")
        }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        countryCode: String? = null,
        setLogin: Boolean = false,
        // Sends no cookie even when signed in. ANDROID_MUSIC answers a cookie-authenticated
        // request with 400, but serves public pages (lyrics) without one.
        withoutLogin: Boolean = false,
    ) = httpClient.post("browse") {
        ytClient(client, !withoutLogin && (setLogin || (cookie != "" && cookie != null)), isUsingReferer = false, anonymous = withoutLogin)

        if (continuation != null && browseId != null) {
            setBody(
                BrowseBody(
                    context = client.toContext(locale, visitorData),
                    browseId = browseId.ifEmpty { null },
                    params = params,
                    continuation = continuation,
                ),
            )
        } else if (continuation != null) {
            setBody(
                BrowseBody(
                    context = client.toContext(locale, visitorData),
                    params = params,
                    continuation = continuation,
                ),
            )
        } else if (countryCode != null) {
            setBody(
                BrowseBody(
                    context = client.toContext(locale, visitorData),
                    browseId = if (browseId.isNullOrEmpty()) null else browseId,
                    params = params,
                    formData = FormData(listOf(countryCode)),
                ),
            )
        } else {
            setBody(
                BrowseBody(
                    context = client.toContext(locale, visitorData),
                    browseId = if (browseId.isNullOrEmpty()) null else browseId,
                    params = params,
                ),
            )
        }
    }

    suspend fun nextCtoken(
        client: YouTubeClient,
        continuation: String,
    ) = httpClient.post("browse") {
        ytClient(client, setLogin = true)
        parameter("ctoken", continuation)
        parameter("continuation", continuation)
        parameter("type", "next")
        parameter("prettyPrint", false)
        setBody(
            BrowseBody(
                context = client.toContext(locale, visitorData),
            ),
        )
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = httpClient.post("next") {
        ytClient(client, setLogin = true)
        setBody(
            NextBody(
                context = client.toContext(locale, visitorData),
                videoId = videoId,
                playlistId = playlistId,
                playlistSetVideoId = playlistSetVideoId,
                index = index,
                params = params,
                continuation = continuation,
            ),
        )
    }

    // Comment writes go out as YouTube Music's own web client does, signed in.
    suspend fun createComment(
        createCommentParams: String,
        text: String,
    ) = httpClient.post("comment/create_comment") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            CreateCommentBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                createCommentParams = createCommentParams,
                commentText = text,
            ),
        )
    }

    suspend fun createCommentReply(
        createReplyParams: String,
        text: String,
    ) = httpClient.post("comment/create_comment_reply") {
        ytClient(WEB_REMIX, setLogin = true)
        setBody(
            CreateCommentReplyBody(
                context = WEB_REMIX.toContext(locale, visitorData),
                createReplyParams = createReplyParams,
                commentText = text,
            ),
        )
    }

    suspend fun performCommentAction(action: String) =
        httpClient.post("comment/perform_comment_action") {
            ytClient(WEB_REMIX, setLogin = true)
            setBody(
                PerformCommentActionBody(
                    context = WEB_REMIX.toContext(locale, visitorData),
                    actions = listOf(action),
                ),
            )
        }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = httpClient.post("music/get_search_suggestions") {
        ytClient(client)
        setBody(
            GetSearchSuggestionsBody(
                context = client.toContext(locale, visitorData),
                input = input,
            ),
        )
    }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = httpClient.post("music/get_queue") {
        ytClient(client)
        setBody(
            GetQueueBody(
                context = client.toContext(locale, visitorData),
                videoIds = videoIds,
                playlistId = playlistId,
            ),
        )
    }

    suspend fun getSwJsData() = httpClient.get("https://music.youtube.com/sw.js_data")

    suspend fun accountMenu(
        customCookie: String? = null,
        client: YouTubeClient,
    ) = httpClient.post("account/account_menu") {
        ytClient(client, setLogin = true, customCookie = customCookie)
        setBody(AccountMenuBody(client.toContext(locale, visitorData)))
    }

    suspend fun getAccountSwitcherEndpoint(customCookie: String? = null) =
        httpClient.get("https://music.youtube.com/getAccountSwitcherEndpoint") {
            ytClient(WEB_REMIX, setLogin = true, customCookie = customCookie)
        }

    suspend fun scrapeYouTube(videoId: String) =
        httpClient.get("https://www.youtube.com/watch?v=$videoId") {
            headers {
                append(HttpHeaders.AcceptLanguage, locale.hl)
                append(HttpHeaders.ContentLanguage, locale.gl)
            }
        }

    @OptIn(ExperimentalTime::class)
    suspend fun initPlayback(
        url: String,
        cpn: String,
        customParams: Map<String, String>? = null,
        playlistId: String?,
    ) = httpClient.get(url) {
        ytClient(WEB_REMIX, true)
        headers {
            append("X-Goog-Event-Time", now().toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().toString())
            append("X-Goog-Request-Time", now().toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().toString())
        }
        parameter("ver", "2")
        parameter("c", "WEB_REMIX")
        parameter("cpn", cpn)
        customParams?.forEach { (key, value) ->
            parameter(key, value)
        }
        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    @OptIn(ExperimentalTime::class)
    suspend fun atr(
        url: String,
        cpn: String,
        customParams: Map<String, String>? = null,
        playlistId: String?,
    ) = httpClient.post(url) {
        ytClient(WEB_REMIX, true)
        headers {
            append("X-Goog-Event-Time", now().toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().toString())
            append("X-Goog-Request-Time", now().toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().toString())
        }
        parameter("cpn", cpn)
        customParams?.forEach { (key, value) ->
            parameter(key, value)
        }
        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    suspend fun subscribeChannel(channelId: String) =
        httpClient.post("subscription/subscribe") {
            ytClient(WEB_REMIX, true)
            setBody(
                SubscribeBody(
                    context = WEB_REMIX.toContext(locale, visitorData),
                    channelIds = listOf(channelId),
                ),
            )
        }

    suspend fun unsubscribeChannel(channelId: String) =
        httpClient.post("subscription/unsubscribe") {
            ytClient(WEB_REMIX, true)
            setBody(
                SubscribeBody(
                    context = WEB_REMIX.toContext(locale, visitorData),
                    channelIds = listOf(channelId),
                ),
            )
        }

    suspend fun addToLiked(videoId: String) =
        httpClient.post("like/like") {
            ytClient(WEB_REMIX, true)
            setBody(
                LikeBody(
                    context = WEB_REMIX.toContext(locale, visitorData),
                    target = LikeBody.Target(videoId),
                ),
            )
        }

    suspend fun dislike(videoId: String) =
        httpClient.post("like/dislike") {
            ytClient(WEB_REMIX, true)
            setBody(
                LikeBody(
                    context = WEB_REMIX.toContext(locale, visitorData),
                    target = LikeBody.Target(videoId),
                ),
            )
        }

    suspend fun removeFromLiked(videoId: String) =
        httpClient.post("like/removelike") {
            ytClient(WEB_REMIX, true)
            setBody(
                LikeBody(
                    context = WEB_REMIX.toContext(locale, visitorData),
                    target = LikeBody.Target(videoId),
                ),
            )
        }

    fun download(
        url: String,
        pathString: String,
        maxRetries: Int = 3,
        parallelDownloads: Int = DEFAULT_PARALLEL_DOWNLOADS,
    ): Flow<Triple<Boolean, Float, Int>> =
        channelFlow {
            val fileSystem = FileSystem.SYSTEM
            val path = pathString.toPath()
            val downloadBufferSize = 256 * 1024

            with(getDownloadClient()) {
                var lastException: Throwable? = null

                // First, check if server supports Range requests
                val supportsRange =
                    try {
                        val rangeResponse =
                            head(url) {
                                header("User-Agent", DOWNLOAD_USER_AGENT)
                                header("Range", "bytes=0-0")
                            }
                        rangeResponse.headers["Content-Range"] != null ||
                            rangeResponse.status.value in 200..299
                    } catch (e: Exception) {
                        false
                    }

                // Get file size
                val fileSize =
                    try {
                        head(url) {
                            header("User-Agent", DOWNLOAD_USER_AGENT)
                        }.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L
                    } catch (e: Exception) {
                        0L
                    }

                // If server supports Range and file is large enough, use parallel download
                if (supportsRange && fileSize > CHUNK_SIZE * 2) {
                    parallelDownload(
                        url = url,
                        path = path,
                        fileSize = fileSize,
                        parallelDownloads = parallelDownloads,
                        downloadBufferSize = downloadBufferSize,
                        maxRetries = maxRetries,
                        onProgress = { downloaded, speed ->
                            trySend(Triple(false, downloaded, speed))
                        },
                        onComplete = { success, exception ->
                            lastException = exception
                            trySend(Triple(true, if (success) 1f else 0f, 0))
                        },
                    )
                } else {
                    // Fallback to single-threaded download
                    singleThreadedDownload(
                        url = url,
                        path = path,
                        maxRetries = maxRetries,
                        downloadBufferSize = downloadBufferSize,
                        onProgress = { downloaded, speed ->
                            trySend(Triple(false, downloaded, speed))
                        },
                        onComplete = { success, exception ->
                            lastException = exception
                            trySend(Triple(true, if (success) 1f else 0f, 0))
                        },
                    )
                }
            }
        }

    private suspend fun HttpClient.parallelDownload(
        url: String,
        path: Path,
        fileSize: Long,
        parallelDownloads: Int,
        downloadBufferSize: Int,
        maxRetries: Int,
        onProgress: suspend (Float, Int) -> Unit,
        onComplete: (Boolean, Throwable?) -> Unit,
    ) {
        val fileSystem = FileSystem.SYSTEM
        val chunkSize = fileSize / parallelDownloads
        val tempDir = path.parent?.let { it / "temp_chunks_${path.name}" } ?: throw IllegalArgumentException("Path has no parent")

        try {
            // Create temp directory
            fileSystem.createDirectories(tempDir)
            val tempFiles =
                (0 until parallelDownloads).map { index ->
                    tempDir / "chunk_$index.tmp"
                }

            // Download each chunk in parallel
            coroutineScope {
                val jobs =
                    (0 until parallelDownloads).map { index ->
                        val startByte = index * chunkSize
                        val endByte = if (index == parallelDownloads - 1) fileSize - 1L else (index + 1L) * chunkSize - 1L

                        launch {
                            var attempt = 0
                            var success = false

                            while (attempt <= maxRetries && !success) {
                                try {
                                    if (attempt > 0) {
                                        delay(500L * (1L shl (attempt - 1)).coerceAtMost(10000L))
                                    }

                                    prepareRequest {
                                        url(url)
                                        header("User-Agent", DOWNLOAD_USER_AGENT)
                                        header("Accept", "*/*")
                                        header("Range", "bytes=$startByte-$endByte")
                                    }.execute { res ->
                                        if (res.status.value != 206) {
                                            throw IllegalStateException("Server returned ${res.status.value} instead of 206 for Range request")
                                        }
                                        val channel = res.bodyAsChannel()
                                        fileSystem.sink(tempFiles[index]).buffer().use { sink ->
                                            while (!channel.isClosedForRead) {
                                                val packet = channel.readRemaining(downloadBufferSize.toLong())
                                                while (!packet.exhausted()) {
                                                    val bytes = packet.readByteArray()
                                                    sink.write(bytes)
                                                }
                                            }
                                        }
                                    }
                                    success = true
                                    Logger.d(TAG, "Chunk $index downloaded: $startByte-$endByte")
                                } catch (e: Exception) {
                                    Logger.e(TAG, "Chunk $index download failed: ${e.message}")
                                    attempt++
                                    if (attempt > maxRetries) {
                                        throw e
                                    }
                                }
                            }
                        }
                    }

                // Wait for all chunks and track progress
                var completedChunks = 0

                // Simplified progress tracking - emit after each chunk completes
                jobs.forEach { job ->
                    launch {
                        job.join()
                        completedChunks++
                        val downloaded = completedChunks * chunkSize
                        val progress = downloaded.toFloat() / fileSize.toFloat()
                        onProgress(progress, 0)
                    }
                }

                // Wait for all to complete
                jobs.forEach { it.join() }
            }

            // Merge all chunks into final file
            Logger.d(TAG, "Merging chunks into $path")
            fileSystem.sink(path).buffer().use { finalSink ->
                tempFiles.forEach { tempFile ->
                    if (fileSystem.exists(tempFile)) {
                        fileSystem.source(tempFile).use { source ->
                            finalSink.writeAll(source)
                        }
                        fileSystem.delete(tempFile)
                    }
                }
            }

            // Cleanup temp directory
            fileSystem.delete(tempDir)

            Logger.d(TAG, "Parallel download completed: $fileSize bytes")
            onComplete(true, null)
        } catch (e: Exception) {
            Logger.e(TAG, "Parallel download failed: ${e.message}")
            // Cleanup temp directory
            try {
                if (fileSystem.exists(tempDir)) {
                    fileSystem.deleteRecursively(tempDir)
                }
            } catch (ignored: Exception) {
                // Ignore cleanup errors
            }
            onComplete(false, e)
        }
    }

    private suspend fun HttpClient.singleThreadedDownload(
        url: String,
        path: Path,
        maxRetries: Int,
        downloadBufferSize: Int,
        onProgress: suspend (Float, Int) -> Unit,
        onComplete: (Boolean, Throwable?) -> Unit,
    ) {
        val fileSystem = FileSystem.SYSTEM
        var lastException: Throwable? = null
        var downloadedBytes = 0L
        var jobDone = 0

        repeat(maxRetries + 1) { attempt ->
            if (jobDone == 1) return@repeat

            if (attempt > 0) {
                try {
                    if (fileSystem.exists(path)) {
                        fileSystem.delete(path)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                downloadedBytes = 0L
                val delayMs = (500L * (1 shl (attempt - 1))).coerceAtMost(10000L)
                Logger.d(TAG, "Retry attempt $attempt after ${delayMs}ms delay")
                delay(delayMs)
            }

            try {
                val length =
                    head(url) {
                        header("User-Agent", DOWNLOAD_USER_AGENT)
                    }.headers[HttpHeaders.ContentLength]?.toLong() ?: 0

                coroutineScope {
                    val downloadJob =
                        launch {
                            runCatching {
                                prepareRequest {
                                    url(url)
                                    header("User-Agent", DOWNLOAD_USER_AGENT)
                                    header("Accept", "*/*")
                                }.execute { res ->
                                    val channel = res.bodyAsChannel()
                                    fileSystem.sink(path).buffer().use { sink ->
                                        while (!channel.isClosedForRead) {
                                            val packet = channel.readRemaining(downloadBufferSize.toLong())
                                            while (!packet.exhausted()) {
                                                val bytes = packet.readByteArray()
                                                sink.write(bytes)
                                                downloadedBytes += bytes.size
                                            }
                                        }
                                    }
                                }
                            }.onSuccess {
                                Logger.d(TAG, "Download completed: $downloadedBytes bytes")
                                jobDone = 1
                            }.onFailure { e ->
                                Logger.e(TAG, "Download failed: ${e.message}")
                                lastException = e
                                jobDone = 1
                            }
                        }

                    val emitJob =
                        launch {
                            var lastEmittedProgress = -1f
                            while (jobDone < 1) {
                                delay(200)
                                if (length > 0) {
                                    val progress = downloadedBytes.toFloat() / length
                                    val progressDiff =
                                        if (progress > lastEmittedProgress) {
                                            progress - lastEmittedProgress
                                        } else {
                                            lastEmittedProgress - progress
                                        }
                                    if (progressDiff >= 0.01f || progress >= 1f) {
                                        val elapsedSeconds = (downloadedBytes / 1024.0) / 200.0
                                        val speed = if (elapsedSeconds > 0) (downloadedBytes / elapsedSeconds / 1024).toInt() else 0
                                        lastEmittedProgress = progress
                                        onProgress(progress, speed)
                                    }
                                }
                            }
                        }

                    downloadJob.join()
                    emitJob.cancel()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Download attempt $attempt failed: ${e.message}")
                lastException = e
                if (attempt == maxRetries) {
                    jobDone = 1
                }
            }
        }

        Logger.d(TAG, "Download finished: $downloadedBytes bytes")
        val isSuccess = lastException == null && downloadedBytes > 0
        onComplete(isSuccess, lastException)
    }

    suspend fun is403Url(url: String): Boolean {
        return try {
            return httpClient.head(url).status.value in 400..499
        } catch (e: Exception) {
            e.printStackTrace()
            true
        }
    }

    companion object {
        /**
         * The repository this build takes updates from. Deliberately not the upstream project:
         * this binary carries local patches that an official release would silently revert, so an
         * upstream release is not an upgrade for it.
         */
        private const val UPDATE_REPO = "CLPow/refine_simpmusic"

        private const val TIDAL_AUTH_URL = "https://auth.tidal.com/v1/oauth2/token"
        private const val TIDAL_SEARCH_URL = "https://tidal.com/v2/client-search/"

        // Remote config (TIDAL credentials) hosted on GitHub raw, fetched on each app launch.
        // Credentials are NOT hard-coded in source — they live only in this remote file,
        // kept in a separate repo (simpmusic-files) so the main repo stays credential-free.
        const val TIDAL_REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/maxrave-dev/simpmusic-files/refs/heads/main/remote-config.json"
    }
}

expect fun getCountry(): String

expect fun getLanguage(): String