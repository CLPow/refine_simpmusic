package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.Tabs
import com.eygraber.uri.toKmpUri
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.kotlinytmusicscraper.YouTube.Companion.DEFAULT_VISITOR_DATA
import com.maxrave.kotlinytmusicscraper.extractor.ExtractSource
import com.maxrave.kotlinytmusicscraper.extractor.contentLengthOf
import com.maxrave.kotlinytmusicscraper.extractor.orderByAudioTrack
import com.maxrave.kotlinytmusicscraper.models.AccountInfo
import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.kotlinytmusicscraper.models.Artist
import com.maxrave.kotlinytmusicscraper.models.ArtistItem
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint
import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import com.maxrave.kotlinytmusicscraper.models.MediaType
import com.maxrave.kotlinytmusicscraper.models.MusicCarouselShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.models.ReturnYouTubeDislikeResponse
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.SearchSuggestions
import com.maxrave.kotlinytmusicscraper.models.SongInfo
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.VideoItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.models.YTItemType
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient.Companion.WEB
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient.Companion.WEB_REMIX
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.maxrave.kotlinytmusicscraper.models.YouTubeLyrics
import com.maxrave.kotlinytmusicscraper.models.MusicQueueRenderer
import com.maxrave.kotlinytmusicscraper.models.YouTubeLocale
import com.maxrave.kotlinytmusicscraper.models.getContinuation
import com.maxrave.kotlinytmusicscraper.models.oddElements
import com.maxrave.kotlinytmusicscraper.models.response.AccountSwitcherEndpointResponse
import com.maxrave.kotlinytmusicscraper.models.response.AddItemYouTubePlaylistResponse
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.CommentsResponse
import com.maxrave.kotlinytmusicscraper.models.response.CreatePlaylistResponse
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.kotlinytmusicscraper.models.response.GetQueueResponse
import com.maxrave.kotlinytmusicscraper.models.response.GetSearchSuggestionsResponse
import com.maxrave.kotlinytmusicscraper.models.response.LikeStatus
import com.maxrave.kotlinytmusicscraper.models.response.NextAndroidMusicResponse
import com.maxrave.kotlinytmusicscraper.models.response.NextResponse
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.kotlinytmusicscraper.models.response.SearchResponse
import com.maxrave.kotlinytmusicscraper.models.response.SimpMusicChartResponse
import com.maxrave.kotlinytmusicscraper.models.response.toLikeStatus
import com.maxrave.kotlinytmusicscraper.models.response.toListAccountInfo
import com.maxrave.kotlinytmusicscraper.models.sponsorblock.SkipSegments
import com.maxrave.kotlinytmusicscraper.models.youtube.Transcript
import com.maxrave.kotlinytmusicscraper.models.youtube.YouTubeInitialPage
import com.maxrave.kotlinytmusicscraper.models.youtube.tryDecodeText
import com.maxrave.kotlinytmusicscraper.pages.AlbumPage
import com.maxrave.kotlinytmusicscraper.pages.ArtistPage
import com.maxrave.kotlinytmusicscraper.pages.BrowseResult
import com.maxrave.kotlinytmusicscraper.pages.CommentItem
import com.maxrave.kotlinytmusicscraper.pages.CommentsPage
import com.maxrave.kotlinytmusicscraper.pages.CommentsPageParser
import com.maxrave.kotlinytmusicscraper.pages.ExplorePage
import com.maxrave.kotlinytmusicscraper.pages.MoodAndGenres
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.pages.NextResult
import com.maxrave.kotlinytmusicscraper.pages.PlaylistPage
import com.maxrave.kotlinytmusicscraper.pages.RelatedPage
import com.maxrave.kotlinytmusicscraper.pages.SearchPage
import com.maxrave.kotlinytmusicscraper.pages.SearchResult
import com.maxrave.kotlinytmusicscraper.pages.SearchSuggestionPage
import com.maxrave.kotlinytmusicscraper.parser.fromPlaylistContinuationToTrackWithSetVideoId
import com.maxrave.kotlinytmusicscraper.parser.fromPlaylistToTrackWithSetVideoId
import com.maxrave.kotlinytmusicscraper.parser.getContinuePlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.getReloadParams
import com.maxrave.kotlinytmusicscraper.parser.getSuggestionSongItems
import com.maxrave.kotlinytmusicscraper.parser.hasReloadParams
import com.maxrave.logger.Logger
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import kotlin.jvm.JvmInline
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "YouTubeScraper"

private const val PLAYER_RETRY_DELAY_MS = 700L

class YouTube {
    private val ytMusic = Ytmusic()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var cookiePath: Path?
        get() = ytMusic.cookiePath
        set(value) {
            ytMusic.cookiePath = value
        }

    var locale: YouTubeLocale
        get() = ytMusic.locale
        set(value) {
            ytMusic.locale = value
        }

    var visitorData: String?
        get() = ytMusic.visitorData
        set(value) {
            ytMusic.visitorData = value
        }

    var dataSyncId: String?
        get() = ytMusic.dataSyncId
        set(value) {
            ytMusic.dataSyncId = value
        }

    var cookie: String?
        get() = ytMusic.cookie
        set(value) {
            if (value != ytMusic.cookie) signInExpired.value = false
            ytMusic.cookie = value
        }

    val signInExpired = MutableStateFlow(false)

    suspend fun verifySignIn(): Boolean? {
        if (cookie.isNullOrEmpty()) {
            signInExpired.value = false
            return true
        }
        val body =
            try {
                val response = ytMusic.accountMenu(null, WEB_REMIX)
                if (response.status.value !in 200..299) return null
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        val signedIn = body.contains("activeAccountHeaderRenderer")
        signInExpired.value = !signedIn
        if (!signedIn) Logger.w(TAG, "Saved sign-in is no longer accepted by YouTube")
        return signedIn
    }

    var pageId: String?
        get() = ytMusic.pageId
        set(value) {
            ytMusic.pageId = value
        }

    var authUser: Int
        get() = ytMusic.authUser
        set(value) {
            ytMusic.authUser = value
        }

    suspend fun search(
        query: String,
        filter: SearchFilter,
    ): Result<SearchResult> =
        runCatching {
            val response = ytMusic.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
            SearchResult(
                items =
                    response.contents
                        ?.tabbedSearchResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicShelfRenderer
                        ?.contents
                        ?.mapNotNull {
                            SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
                        }.orEmpty(),
                listPodcast =
                    response.contents
                        ?.tabbedSearchResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicShelfRenderer
                        ?.contents
                        ?.mapNotNull {
                            SearchPage.toPodcast(it.musicResponsiveListItemRenderer)
                        }.orEmpty(),
                continuation =
                    response.contents
                        ?.tabbedSearchResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicShelfRenderer
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    suspend fun searchSummary(query: String): Result<SearchResponse> =
        runCatching { ytMusic.search(WEB_REMIX, query).body<SearchResponse>() }

    val accountGuard get() = ytMusic.accountGuard

    fun resetConnections() = ytMusic.resetConnections()

    suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        runCatching {
            val response = ytMusic.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
            SearchResult(
                items =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.contents
                        ?.mapNotNull {
                            SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
                        }!!,
                listPodcast =
                    response.continuationContents.musicShelfContinuation.contents
                        .mapNotNull {
                            SearchPage.toPodcast(it.musicResponsiveListItemRenderer)
                        }.orEmpty(),
                continuation =
                    response.continuationContents.musicShelfContinuation.continuations
                        ?.getContinuation(),
            )
        }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = ytMusic.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            val playlistId =
                response.microformat
                    ?.microformatDataRenderer
                    ?.urlCanonical
                    ?.substringAfterLast('=')!!
            val albumItem =
                AlbumItem(
                    browseId = browseId,
                    playlistId = playlistId,
                    title =
                        response.contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicResponsiveHeaderRenderer
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: "",
                    artists =
                        response.contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicResponsiveHeaderRenderer
                            ?.straplineTextOne
                            ?.runs
                            ?.oddElements()
                            ?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            }!!,
                    year =
                        response.contents.twoColumnBrowseResultsRenderer.tabs
                            .firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicResponsiveHeaderRenderer
                            ?.subtitle
                            ?.runs
                            ?.lastOrNull()
                            ?.text
                            ?.toIntOrNull(),
                    thumbnail =
                        response.contents.twoColumnBrowseResultsRenderer.tabs
                            .firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicResponsiveHeaderRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()!!,
                )
            AlbumPage(
                album = albumItem,
                songs =
                    if (withSongs) {
                        albumSongs(
                            response.contents
                                .twoColumnBrowseResultsRenderer
                                .secondaryContents
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicShelfRenderer
                                ?.contents,
                            albumItem,
                        ).getOrThrow()
                    } else {
                        emptyList()
                    },
                description =
                    getDescriptionAlbum(
                        response.contents.twoColumnBrowseResultsRenderer.tabs
                            .firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicResponsiveHeaderRenderer
                            ?.description
                            ?.musicDescriptionShelfRenderer
                            ?.description
                            ?.runs,
                    ),
                duration =
                    response.contents.twoColumnBrowseResultsRenderer.tabs
                        .firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicResponsiveHeaderRenderer
                        ?.secondSubtitle
                        ?.runs
                        ?.get(2)
                        ?.text ?: "",
                thumbnails =
                    response.contents.twoColumnBrowseResultsRenderer.tabs
                        .firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicResponsiveHeaderRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.thumbnail,
                otherVersion =
                    response.contents
                        .twoColumnBrowseResultsRenderer
                        .secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull {
                            AlbumPage.fromMusicTwoRowItemRenderer(
                                it.musicTwoRowItemRenderer,
                            )
                        } ?: emptyList(),
            )
        }

    private fun getDescriptionAlbum(runs: List<Run>?): String {
        var description = ""
        if (!runs.isNullOrEmpty()) {
            for (run in runs) {
                description += run.text
            }
        }
        Logger.d("description", description)
        return description
    }

    private fun albumSongs(
        content: List<MusicShelfRenderer.Content>?,
        album: AlbumItem,
    ): Result<List<SongItem>> =
        runCatching {
            if (content == null) {
                return@runCatching emptyList()
            } else {
                return@runCatching content.mapNotNull {
                    AlbumPage.fromMusicResponsiveListItemRenderer(it.musicResponsiveListItemRenderer, album)
                }
            }
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            val response = ytMusic.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            ArtistPage(
                artist =
                    ArtistItem(
                        id = browseId,
                        title =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text!!,
                        thumbnail =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.foregroundThumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl()!!,
                        shuffleEndpoint =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.playButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint,
                        radioEndpoint =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.startRadioButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint,
                    ),
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull(ArtistPage::fromSectionListRendererContent)!!,
                description =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.description
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                subscribers =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.subscriptionButton
                        ?.subscribeButtonRenderer
                        ?.longSubscriberCountText
                        ?.runs
                        ?.get(
                            0,
                        )?.text,
                view =
                    response.contents.singleColumnBrowseResultsRenderer.tabs[0]
                        .tabRenderer.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicDescriptionShelfRenderer
                        ?.subheader
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
            )
        }

    suspend fun getYouTubePlaylistFullTracksWithSetVideoId(playlistId: String): Result<List<Pair<SongItem, String>>> =
        runCatching {
            val plId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            val listPair = mutableListOf<Pair<SongItem, String>>()
            val response = ytMusic.playlist(plId).body<BrowseResponse>()
            listPair.addAll(
                response.fromPlaylistToTrackWithSetVideoId(),
            )
            var continuation = response.getPlaylistContinuation()
            val seenContinuations = mutableSetOf<String>()
            while (continuation != null && seenContinuations.add(continuation)) {
                val continuationResponse =
                    runCatching {
                        ytMusic
                            .browse(
                                client = WEB_REMIX,
                                setLogin = true,
                                params = null,
                                continuation = continuation,
                            ).body<BrowseResponse>()
                    }.getOrNull() ?: break
                listPair.addAll(
                    continuationResponse.fromPlaylistContinuationToTrackWithSetVideoId(),
                )
                continuation = continuationResponse.getPlaylistContinuation()
            }

            return@runCatching listPair
        }

    suspend fun getSuggestionsTrackForPlaylist(playlistId: String): Result<Pair<String?, List<SongItem>?>?> =
        runCatching {
            val initialResponse =
                ytMusic
                    .playlist(
                        if (playlistId.startsWith("VL")) playlistId else "VL$playlistId",
                    ).body<BrowseResponse>()
            var continuation = initialResponse.getPlaylistContinuation()
            Logger.d(TAG, "YouTube: getSuggestionsTrackForPlaylist: $continuation")
            while (continuation != null) {
                val continuationResponse =
                    ytMusic
                        .browse(
                            client = WEB_REMIX,
                            setLogin = true,
                            params = "wAEB",
                            continuation = continuation,
                        ).body<BrowseResponse>()
                Logger.d(TAG, "YouTube: getSuggestionsTrackForPlaylist: ${continuationResponse.getReloadParams()}")
                if (continuationResponse.hasReloadParams()) {
                    return@runCatching Pair(continuationResponse.getReloadParams(), continuationResponse.getSuggestionSongItems())
                } else {
                    continuation = continuationResponse.getContinuePlaylistContinuation()
                }
            }
            return@runCatching null
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                ytMusic
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VL$playlistId",
                        setLogin = true,
                    ).body<BrowseResponse>()
            val header =
                response.header?.musicDetailHeaderRenderer
                    ?: response.header
                        ?.musicEditablePlaylistDetailHeaderRenderer
                        ?.header
                        ?.musicDetailHeaderRenderer!!
            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title =
                            header.title.runs
                                ?.firstOrNull()
                                ?.text!!,
                        author =
                            header.subtitle.runs?.getOrNull(2)?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        songCountText =
                            header.secondSubtitle.runs
                                ?.firstOrNull()
                                ?.text,
                        thumbnail = header.thumbnail.croppedSquareThumbnailRenderer?.getThumbnailUrl()!!,
                        playEndpoint = null,
                        shuffleEndpoint =
                            header.menu.menuRenderer.topLevelButtons
                                ?.firstOrNull()
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint!!,
                        radioEndpoint =
                            header.menu.menuRenderer.items
                                .find {
                                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                }?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint!!,
                    ),
                songs =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
                        ?.mapNotNull {
                            PlaylistPage.fromMusicResponsiveListItemRenderer(it.musicResponsiveListItemRenderer)
                        }!!,
                songsContinuation =
                    response.contents.singleColumnBrowseResultsRenderer.tabs
                        .firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.continuations
                        ?.getContinuation(),
                continuation =
                    response.contents.singleColumnBrowseResultsRenderer.tabs
                        .firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    suspend fun customQuery(
        browseId: String?,
        params: String? = null,
        continuation: String? = null,
        country: String? = null,
        setLogin: Boolean = true,
        withoutLogin: Boolean = false,
    ): Result<BrowseResponse> =
        try {
            Result.success(
                ytMusic.browse(WEB_REMIX, browseId, params, continuation, country, setLogin, withoutLogin).body<BrowseResponse>(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun getSkipSegments(videoId: String): Result<List<SkipSegments>> =
        runCatching {
            ytMusic.getSkipSegments(videoId).body<List<SkipSegments>>()
        }

    suspend fun newRelease(): Result<ExplorePage> =
        runCatching {
            val response =
                ytMusic.browse(WEB_REMIX, browseId = "FEmusic_new_releases").body<BrowseResponse>()
            ExplorePage(
                released =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(RelatedPage::fromMusicTwoRowItemRenderer)
                        .orEmpty()
                        .mapNotNull {
                            if (it.type == YTItemType.ALBUM) it as? AlbumItem else null
                        },
                musicVideo =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.lastOrNull()
                        ?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicTwoRowItemRenderer
                        }?.mapNotNull(
                            ArtistPage::fromMusicTwoRowItemRenderer,
                        ).orEmpty()
                        .mapNotNull {
                            if (it.type == YTItemType.VIDEO) it as? VideoItem else null
                        },
            )
        }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> =
        runCatching {
            val response = ytMusic.browse(WEB_REMIX, browseId = "FEmusic_moods_and_genres").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents!!
                .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
        }

    suspend fun browse(
        browseId: String,
        params: String?,
    ): Result<BrowseResult> =
        runCatching {
            val response = ytMusic.browse(WEB_REMIX, browseId = browseId, params = params).body<BrowseResponse>()
            BrowseResult(
                title =
                    response.header
                        ?.musicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                items =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            when {
                                content.gridRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.gridRenderer.header
                                                ?.gridHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.gridRenderer.items
                                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                                .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                    )
                                }

                                content.musicCarouselShelfRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.musicCarouselShelfRenderer.header
                                                ?.musicCarouselShelfBasicHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.musicCarouselShelfRenderer.contents
                                                .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                                .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                    )
                                }

                                else -> {
                                    null
                                }
                            }
                        }.orEmpty(),
            )
        }

    suspend fun getFullMetadata(videoId: String): Result<YouTubeInitialPage> =
        runCatching {
            val ytScrape = ytMusic.scrapeYouTube(videoId).body<String>()
            var response = ""
            val ksoupHtmlParser =
                KsoupHtmlParser(
                    object : KsoupHtmlHandler {
                        override fun onText(text: String) {
                            super.onText(text)
                            if (text.contains("var ytInitialPlayerResponse")) {
                                val temp = text.replace("var ytInitialPlayerResponse = ", "").dropLast(1)
                                Logger.d("Scrape", "Temp $temp")
                                response = temp.trimIndent()
                            }
                        }
                    },
                )
            ksoupHtmlParser.write(ytScrape)
            ksoupHtmlParser.end()
            val json = Json { ignoreUnknownKeys = true }
            return@runCatching json.decodeFromString<YouTubeInitialPage>(response)
        }

    suspend fun getLikedInfo(videoId: String): Result<LikeStatus> =
        runCatching {
            val response =
                ytMusic
                    .next(
                        WEB_REMIX,
                        videoId,
                        null,
                        null,
                        null,
                        null,
                        null,
                    ).body<NextAndroidMusicResponse>()
            val likeStatus =
                response.playerOverlays
                    ?.playerOverlayRenderer
                    ?.actions
                    ?.find { it.likeButtonRenderer != null }
                    ?.likeButtonRenderer
                    ?.likeStatus
                    ?.toLikeStatus()
            Logger.w("YouTube", "Like Status ${response.playerOverlays}")
            return@runCatching likeStatus ?: LikeStatus.INDIFFERENT
        }

    suspend fun getSongInfo(videoId: String): Result<SongInfo> =
        runCatching {
            val ytNext = ytMusic.next(WEB, videoId, null, null, null, null, null).body<NextResponse>()
            val videoSecondary =
                ytNext.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find {
                        it?.videoSecondaryInfoRenderer != null
                    }?.videoSecondaryInfoRenderer
            val videoPrimary =
                ytNext.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find {
                        it?.videoPrimaryInfoRenderer != null
                    }?.videoPrimaryInfoRenderer
            val returnYouTubeDislikeResponse =
                ytMusic.returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()
            return@runCatching SongInfo(
                videoId = videoId,
                author =
                    videoSecondary?.owner?.videoOwnerRenderer?.title?.runs?.firstOrNull()?.text?.replace(
                        Regex(" - Topic| - Chủ đề|"),
                        "",
                    ),
                authorId =
                    videoSecondary
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.navigationEndpoint
                        ?.browseEndpoint
                        ?.browseId,
                authorThumbnail =
                    videoSecondary
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.find {
                            it.height == 48
                        }?.url
                        ?.replace("s48", "s960"),
                description = videoSecondary?.attributedDescription?.content,
                subscribers =
                    videoSecondary
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.subscriberCountText
                        ?.simpleText,
                uploadDate = videoPrimary?.dateText?.simpleText,
                viewCount = returnYouTubeDislikeResponse.viewCount,
                like = returnYouTubeDislikeResponse.likes,
                dislike = returnYouTubeDislikeResponse.dislikes,
            )
        }

    suspend fun comments(videoId: String): Result<CommentsPage?> =
        runCatching {
            val next = ytMusic.next(WEB_REMIX, videoId, null, null, null, null).body<NextResponse>()
            val token = CommentsPageParser.commentsTabToken(next) ?: return@runCatching null
            CommentsPageParser
                .parse(ytMusic.next(WEB_REMIX, null, null, null, null, null, token).body<CommentsResponse>())
                .takeIf { it.header != null || it.comments.isNotEmpty() }
        }

    suspend fun commentsPage(token: String): Result<CommentsPage> =
        runCatching {
            CommentsPageParser.parse(ytMusic.next(WEB_REMIX, null, null, null, null, null, token).body<CommentsResponse>())
        }

    suspend fun createComment(
        createCommentParams: String,
        text: String,
    ): Result<CommentItem?> =
        runCatching {
            CommentsPageParser.parseCreated(ytMusic.createComment(createCommentParams, text).body<CommentsResponse>())
        }

    suspend fun createCommentReply(
        createReplyParams: String,
        text: String,
    ): Result<CommentItem?> =
        runCatching {
            CommentsPageParser.parseCreated(ytMusic.createCommentReply(createReplyParams, text).body<CommentsResponse>())
        }

    suspend fun performCommentAction(action: String): Result<Unit> =
        runCatching {
            ytMusic.performCommentAction(action)
            Unit
        }

    suspend fun newPipePlayer(
        videoId: String,
        tempRes: PlayerResponse,
        extracted: (suspend () -> List<Pair<Int, String>>)? = null,
        preferredAudioLanguage: String? = null,
    ): PlayerResponse? {
        val listUrlSig = mutableListOf<String>()
        var decodedSigResponse: PlayerResponse?
        var sigResponse: PlayerResponse?
        Logger.d(TAG, "YouTube TempRes ${tempRes.playabilityStatus}")
        if (tempRes.playabilityStatus.status != "OK") {
            return null
        } else {
            sigResponse = tempRes
        }
        val streamsList = (extracted?.invoke() ?: ytMusic.getNewPipePlayer(videoId)).orderByAudioTrack(preferredAudioLanguage)
        if (streamsList.isEmpty()) return null

        decodedSigResponse =
            sigResponse.copy(
                streamingData =
                    sigResponse.streamingData?.copy(
                        formats =
                            sigResponse.streamingData.formats?.map { format ->
                                val url = streamsList.find { it.first == format.itag }?.second
                                format.copy(
                                    url = url,
                                    contentLength = url?.let(::contentLengthOf) ?: format.contentLength,
                                )
                            },
                        adaptiveFormats =
                            sigResponse.streamingData.adaptiveFormats.map { adaptiveFormats ->
                                val url = streamsList.find { it.first == adaptiveFormats.itag }?.second
                                adaptiveFormats.copy(
                                    url = url,
                                    contentLength = url?.let(::contentLengthOf) ?: adaptiveFormats.contentLength,
                                )
                            },
                        hlsManifestUrl = streamsList.firstOrNull { it.first == 96 }?.second,
                    ),
            )
        decodedSigResponse =
            decodedSigResponse.copy(
                streamingData =
                    decodedSigResponse.streamingData?.copy(
                        formats =
                            decodedSigResponse.streamingData.formats?.let { formats ->
                                val copy = formats.toMutableList()
                                streamsList
                                    .filter {
                                        isManifestUrl(it.second)
                                    }.forEach { manifest ->
                                        copy.add(
                                            PlayerResponse.StreamingData.Format(
                                                itag = manifest.first,
                                                url = manifest.second,
                                                mimeType = "",
                                                bitrate = 0,
                                                width = if (manifest.first == 96) 1920 else 1080,
                                                height = if (manifest.first == 96) 1080 else 720,
                                                contentLength = 0,
                                                quality = "",
                                                fps = 0,
                                                qualityLabel = "",
                                                averageBitrate = 0,
                                                audioQuality = "",
                                                approxDurationMs = "",
                                                audioSampleRate = 0,
                                                audioChannels = 0,
                                                loudnessDb = 0.0,
                                                lastModified = 0,
                                                signatureCipher = "",
                                            ),
                                        )
                                    }
                                copy.filter { it.itag != 0 }
                                copy
                            },
                    ),
            )
        listUrlSig.addAll(
            (
                decodedSigResponse
                    .streamingData
                    ?.adaptiveFormats
                    ?.mapNotNull { it.url }
                    ?.toMutableList() ?: mutableListOf()
            ).apply {
                decodedSigResponse
                    .streamingData
                    ?.formats
                    ?.mapNotNull { it.url }
                    ?.let { addAll(it) }
            },
        )
        return if (listUrlSig.isNotEmpty()) decodedSigResponse else null
    }

    fun isManifestUrl(url: String): Boolean = url.contains(".m3u8") || url.contains(".mpd") || url.contains("manifest")

    @OptIn(ExperimentalTime::class)
    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        noLogIn: Boolean = false,
        preferredAudioLanguage: String? = null,
    ): Result<Triple<String?, PlayerResponse, MediaType>> {
        val first = playerOnce(videoId, playlistId, preferredAudioLanguage)
        val failure = first.exceptionOrNull() ?: return first
        if (failure is kotlinx.coroutines.CancellationException) return first
        if ((failure as? io.ktor.client.plugins.ResponseException)?.response?.status?.value == 429) return first
        Logger.w(TAG, "player($videoId) failed (${failure.message}); trying once more")
        kotlinx.coroutines.delay(PLAYER_RETRY_DELAY_MS)
        return playerOnce(videoId, playlistId, preferredAudioLanguage)
    }

    private suspend fun playerOnce(
        videoId: String,
        playlistId: String?,
        preferredAudioLanguage: String?,
    ): Result<Triple<String?, PlayerResponse, MediaType>> =
        runCatching {
            val cpn =
                (1..16)
                    .map {
                        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[
                            Random.nextInt(
                                0,
                                64,
                            ),
                        ]
                    }.joinToString("")

            var decodedSigResponse: PlayerResponse? = null
            val extraction =
                ioScope.async {
                    ytMusic.getNewPipePlayer(videoId)
                }
            val tempRes =
                runCatching {
                ytMusic
                    .player(
                        WEB_REMIX,
                        videoId,
                        playlistId,
                        cpn,
                        signatureTimestamp =
                            run {
                                val today = Clock.System.todayIn(TimeZone.UTC)
                                val epoch =
                                    Instant
                                        .fromEpochSeconds(0)
                                        .toLocalDateTime(TimeZone.UTC)
                                        .date
                                epoch.daysUntil(today)
                            },
                    ).body<PlayerResponse>()
                    .let {
                        val fexp =
                            it.streamingData
                                ?.serverAbrStreamingUrl
                                ?.toKmpUri()
                                ?.getQueryParameter("fexp")
                        val playbackTracking = it.playbackTracking
                        it.copy(
                            playbackTracking =
                                playbackTracking?.copy(
                                    atrUrl =
                                        playbackTracking.atrUrl?.copy(
                                            baseUrl =
                                                playbackTracking.atrUrl.baseUrl
                                                    ?.toKmpUri()
                                                    ?.buildUpon()
                                                    ?.apply {
                                                        if (fexp != null) {
                                                            appendQueryParameter("fexp", fexp)
                                                        }
                                                    }?.build()
                                                    ?.toString(),
                                        ),
                                    videostatsPlaybackUrl =
                                        playbackTracking.videostatsPlaybackUrl?.copy(
                                            baseUrl =
                                                playbackTracking.videostatsPlaybackUrl.baseUrl
                                                    ?.toKmpUri()
                                                    ?.buildUpon()
                                                    ?.apply {
                                                        if (fexp != null) {
                                                            appendQueryParameter("fexp", fexp)
                                                        }
                                                    }?.build()
                                                    ?.toString(),
                                        ),
                                    videostatsWatchtimeUrl =
                                        playbackTracking.videostatsWatchtimeUrl?.copy(
                                            baseUrl =
                                                playbackTracking.videostatsWatchtimeUrl.baseUrl
                                                    ?.toKmpUri()
                                                    ?.buildUpon()
                                                    ?.apply {
                                                        if (fexp != null) {
                                                            appendQueryParameter("fexp", fexp)
                                                        }
                                                    }?.build()
                                                    ?.toString(),
                                        ),
                                ),
                        )
                    }
                }.getOrElse {
                    extraction.cancel()
                    throw it
                }

            val response =
                try {
                    newPipePlayer(videoId, tempRes, { extraction.await() }, preferredAudioLanguage)
                } finally {
                    extraction.cancel()
                }
            if (response != null) {
                decodedSigResponse = response
                Logger.d(TAG, "YouTube Player found URL")
            } else {
                Logger.d(TAG, "YouTube Player no URL found")
            }
            if (decodedSigResponse == null) throw RuntimeException("No URL found")
            val firstThumb =
                decodedSigResponse.videoDetails
                    ?.thumbnail
                    ?.thumbnails
                    ?.firstOrNull()
            val thumbnails =
                if (firstThumb?.height == firstThumb?.width && firstThumb != null) MediaType.Song else MediaType.Video
            return@runCatching Triple(
                cpn,
                decodedSigResponse.copy(
                    videoDetails = decodedSigResponse.videoDetails?.copy(),
                    playbackTracking = decodedSigResponse.playbackTracking,
                ),
                thumbnails,
            )
        }

    suspend fun updateWatchTime(
        watchtimeUrl: String,
        watchtimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Result<Int> =
        runCatching {
            val et = watchtimeList.takeLast(2).joinToString(",")
            val watchtime = watchtimeList.dropLast(1).takeLast(2).joinToString(",")
            ytMusic.initPlayback(watchtimeUrl, cpn, mapOf("st" to watchtime, "et" to et), playlistId).status.value.let { status ->
                if (status == 204) {
                    Logger.d(TAG, "watchtime done")
                }
                return@runCatching status
            }
        }

    suspend fun updateWatchTimeFull(
        watchtimeUrl: String,
        cpn: String,
        playlistId: String?,
    ): Result<Int> =
        runCatching {
            val regex = Regex("len=([^&]+)")
            val length =
                regex
                    .find(watchtimeUrl)
                    ?.groupValues
                    ?.firstOrNull()
                    ?.drop(4) ?: "0"
            Logger.d(TAG, length)
            ytMusic.initPlayback(watchtimeUrl, cpn, mapOf("st" to length, "et" to length), playlistId).status.value.let { status ->
                if (status == 204) {
                    Logger.d(TAG, "watchtime full done")
                }
                return@runCatching status
            }
        }

    suspend fun initPlayback(
        playbackUrl: String,
        atrUrl: String,
        watchtimeUrl: String,
        cpn: String,
        playlistId: String?,
    ): Result<Pair<Int, Float>> {
        Logger.d(TAG, "playbackUrl $playbackUrl")
        Logger.d(TAG, "atrUrl $atrUrl")
        Logger.d(TAG, "watchtimeUrl $watchtimeUrl")
        return runCatching {
            ytMusic.initPlayback(playbackUrl, cpn, null, playlistId).status.value.let { status ->
                if (status == 204) {
                    Logger.d(TAG, "playback done")
                    ytMusic.initPlayback(watchtimeUrl, cpn, mapOf("st" to "0", "et" to "5.54"), playlistId).status.value.let { firstWatchTime ->
                        if (firstWatchTime == 204) {
                            Logger.d(TAG, "first watchtime done")
                            delay(5000)
                            ytMusic.atr(atrUrl, cpn, null, playlistId).status.value.let { atr ->
                                if (atr == 204) {
                                    Logger.d(TAG, "atr done")
                                    delay(500)
                                    val secondWatchTime = (round(Random.nextFloat() * 100.0) / 100.0).toFloat() + 12f
                                    ytMusic
                                        .initPlayback(
                                            watchtimeUrl,
                                            cpn,
                                            mapOf<String, String>("st" to "0,5.54", "et" to "5.54,$secondWatchTime"),
                                            playlistId,
                                        ).status.value
                                        .let { watchtime ->
                                            if (watchtime == 204) {
                                                Logger.d(TAG, "watchtime done")
                                                return@runCatching Pair(watchtime, secondWatchTime)
                                            } else {
                                                return@runCatching Pair(watchtime, secondWatchTime)
                                            }
                                        }
                                } else {
                                    return@runCatching Pair(atr, 0f)
                                }
                            }
                        } else {
                            return@runCatching Pair(firstWatchTime, 0f)
                        }
                    }
                } else {
                    return@runCatching Pair(status, 0f)
                }
            }
        }
    }

    suspend fun nextYouTubePlaylists(continuation: String): Result<Pair<List<MusicTwoRowItemRenderer>, String?>> =
        runCatching {
            val res =
                ytMusic
                    .nextCtoken(
                        WEB_REMIX,
                        continuation,
                    )
            Logger.d(TAG, "Next Playlists ${res.bodyAsText()}")
            val response = res.body<BrowseResponse>()
            Pair(
                response
                    .continuationContents
                    ?.gridContinuation
                    ?.items
                    ?.mapNotNull { it.musicTwoRowItemRenderer } ?: emptyList(),
                response.continuationContents
                    ?.gridContinuation
                    ?.continuations
                    ?.getContinuation(),
            )
        }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
    ): Result<NextResult> =
        runCatching {
            val response =
                ytMusic
                    .next(
                        WEB_REMIX,
                        endpoint.videoId,
                        endpoint.playlistId,
                        endpoint.playlistSetVideoId,
                        endpoint.index,
                        endpoint.params,
                        continuation,
                    ).body<NextResponse>()
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.musicQueueRenderer
                        ?.content
                        ?.playlistPanelRenderer
            if (playlistPanelRenderer != null) {
                if (playlistPanelRenderer.contents
                        .lastOrNull()
                        ?.automixPreviewVideoRenderer
                        ?.content
                        ?.automixPlaylistVideoRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint !=
                    null
                ) {
                    return@runCatching next(
                        playlistPanelRenderer.contents
                            .lastOrNull()
                            ?.automixPreviewVideoRenderer
                            ?.content
                            ?.automixPlaylistVideoRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint!!,
                    ).getOrThrow()
                        .let { result ->
                            result.copy(
                                title = playlistPanelRenderer.title,
                                items =
                                    playlistPanelRenderer.contents.mapNotNull {
                                        it.songTrack?.let { renderer ->
                                            NextPage.fromPlaylistPanelVideoRenderer(renderer)
                                        }
                                    } + result.items,
                                lyricsEndpoint =
                                    response.contents.singleColumnMusicWatchNextResultsRenderer
                                        ?.tabbedRenderer
                                        ?.watchNextTabbedResultsRenderer
                                        ?.tabs
                                        ?.tabEndpoint(BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.MUSIC_PAGE_TYPE_TRACK_LYRICS),
                                relatedEndpoint =
                                    response.contents.singleColumnMusicWatchNextResultsRenderer
                                        ?.tabbedRenderer
                                        ?.watchNextTabbedResultsRenderer
                                        ?.tabs
                                        ?.tabEndpoint(BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.MUSIC_PAGE_TYPE_TRACK_RELATED),
                                currentIndex = playlistPanelRenderer.songIndexOf(playlistPanelRenderer.currentIndex),
                                endpoint =
                                    playlistPanelRenderer.contents
                                        .lastOrNull()
                                        ?.automixPreviewVideoRenderer
                                        ?.content
                                        ?.automixPlaylistVideoRenderer
                                        ?.navigationEndpoint
                                        ?.watchPlaylistEndpoint!!,
                            )
                        }
                }
                return@runCatching NextResult(
                    title = playlistPanelRenderer.title,
                    items =
                        playlistPanelRenderer.contents.mapNotNull {
                            it.songTrack?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        },
                    currentIndex = playlistPanelRenderer.songIndexOf(playlistPanelRenderer.currentIndex),
                    lyricsEndpoint =
                        response.contents.singleColumnMusicWatchNextResultsRenderer
                            ?.tabbedRenderer
                            ?.watchNextTabbedResultsRenderer
                            ?.tabs
                            ?.tabEndpoint(BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.MUSIC_PAGE_TYPE_TRACK_LYRICS),
                    relatedEndpoint =
                        response.contents.singleColumnMusicWatchNextResultsRenderer
                            ?.tabbedRenderer
                            ?.watchNextTabbedResultsRenderer
                            ?.tabs
                            ?.tabEndpoint(BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.MUSIC_PAGE_TYPE_TRACK_RELATED),
                    continuation = playlistPanelRenderer.continuations?.getContinuation(),
                    endpoint = endpoint,
                )
            } else {
                val musicPlaylistShelfContinuation = response.continuationContents?.musicPlaylistShelfContinuation!!
                return@runCatching NextResult(
                    items =
                        musicPlaylistShelfContinuation.contents
                            .mapNotNull {
                                it.musicResponsiveListItemRenderer?.let { renderer ->
                                    NextPage.fromMusicResponsiveListItemRenderer(renderer)
                                }
                            }.filter { MusicVideoType.isPlayableSong(it.musicVideoType) },
                    continuation =
                        musicPlaylistShelfContinuation.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation,
                    endpoint =
                        WatchEndpoint(
                            videoId = null,
                            playlistId = null,
                            playlistSetVideoId = null,
                            params = null,
                            index = null,
                            watchEndpointMusicSupportedConfigs = null,
                        ),
                )
            }
        }

    suspend fun youTubeMusicLyrics(videoId: String): Result<YouTubeLyrics?> =
        runCatching {
            val endpoint =
                ytMusic
                    .next(WEB_REMIX, videoId, null, null, null, null)
                    .body<NextResponse>()
                    .contents
                    .singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
                    ?.tabEndpoint(BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.MUSIC_PAGE_TYPE_TRACK_LYRICS)
                    ?: return@runCatching null
            val timed =
                runCatching {
                    YouTubeLyrics.fromAndroidBrowse(
                        ytMusic.browse(ANDROID_MUSIC, endpoint.browseId, endpoint.params, withoutLogin = true).body<JsonElement>(),
                    )
                }.onFailure { Logger.w(TAG, "Timed lyrics for $videoId: ${it.message}") }
                    .getOrNull()
            timed ?: lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() }?.let { YouTubeLyrics(lines = null, plain = it, source = null) }
        }

    suspend fun radioChips(
        videoId: String,
        playlistId: String,
    ): Result<List<RadioChip>> =
        runCatching {
            ytMusic
                .next(WEB_REMIX, videoId, playlistId, null, null, null)
                .body<NextResponse>()
                .contents
                .singleColumnMusicWatchNextResultsRenderer
                ?.tabbedRenderer
                ?.watchNextTabbedResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.musicQueueRenderer
                ?.subHeaderChipCloud
                .toRadioChips()
        }

    data class RadioChip(
        val title: String,
        val playlistId: String?,
        val params: String?,
        val selected: Boolean,
    )

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = ytMusic.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicDescriptionShelfRenderer
                ?.description
                ?.runs
                ?.firstOrNull()
                ?.text
        }

    suspend fun queue(
        videoIds: List<String>? = null,
        playlistId: String? = null,
    ): Result<List<SongItem>> =
        runCatching {
            ytMusic
                .getQueue(WEB_REMIX, videoIds, playlistId)
                .body<GetQueueResponse>()
                .queueDatas
                .mapNotNull {
                    it.content.songTrack?.let { renderer ->
                        NextPage.fromPlaylistPanelVideoRenderer(renderer)
                    }
                }
        }

    suspend fun visitorData(): String? =
        try {
            Json
                .parseToJsonElement(ytMusic.getSwJsData().bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .first { (it as? JsonPrimitive)?.content?.let(VISITOR_DATA_REGEX::containsMatchIn) == true }
                .jsonPrimitive.content
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    suspend fun getAccountListWithPageId(customCookie: String): Result<List<AccountInfo>> =
        runCatching {
            val res =
                ytMusic
                    .getAccountSwitcherEndpoint(customCookie)
                    .bodyAsText()
                    .removePrefix(")]}'\n")
            val accountSwitcherEndpointResponse = ytMusic.normalJson.decodeFromString<AccountSwitcherEndpointResponse>(res)
            Logger.d(TAG, "Account List Response: $accountSwitcherEndpointResponse")
            accountSwitcherEndpointResponse.toListAccountInfo()
        }

    suspend fun getLibraryPlaylists() =
        runCatching {
            ytMusic.browse(WEB_REMIX, "FEmusic_liked_playlists", setLogin = true).body<BrowseResponse>()
        }

    suspend fun getMixedForYou() =
        runCatching {
            ytMusic.browse(WEB_REMIX, "FEmusic_mixed_for_you", setLogin = true).body<BrowseResponse>()
        }

    @JvmInline
    value class SearchFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_SONG: SearchFilter = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO: SearchFilter = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM: SearchFilter = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST: SearchFilter = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST: SearchFilter = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST: SearchFilter = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
            val FILTER_PODCAST: SearchFilter = SearchFilter("EgWKAQJQAWoIEBAQERADEBU%3D")
        }
    }

    suspend fun getYTMusicSearchSuggestions(query: String) =
        runCatching {
            val response = ytMusic.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents
                        ?.getOrNull(0)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            content.searchSuggestionRenderer
                                ?.suggestion
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                        }.orEmpty(),
                recommendedItems =
                    response.contents
                        ?.getOrNull(1)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }.orEmpty(),
            )
        }

    suspend fun getYouTubeCaption(videoId: String) =
        runCatching {
            val ytWeb = ytMusic.player(WEB, videoId, null, null).body<YouTubeInitialPage>()
            ytMusic
                .getYouTubeCaption(
                    ytWeb.captions?.playerCaptionsTracklistRenderer?.captionTracks?.firstOrNull()?.baseUrl?.replace(
                        "&fmt=srv3",
                        "",
                    ) ?: "",
                ).body<Transcript>()
                .tryDecodeText()
        }

    suspend fun scrapeYouTube(videoId: String) =
        runCatching {
            ytMusic.scrapeYouTube(videoId).body<String>()
        }

    suspend fun removeItemYouTubePlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        ytMusic.removeItemYouTubePlaylist(playlistId, videoId, setVideoId).status.value
    }

    suspend fun addPlaylistItem(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        ytMusic.addItemYouTubePlaylist(playlistId, videoId).body<AddItemYouTubePlaylistResponse>()
    }

    suspend fun movePlaylistItem(
        playlistId: String,
        setVideoId: String,
        movedSetVideoIdSuccessor: String? = null,
    ) = runCatching {
        ytMusic.moveItemYouTubePlaylist(playlistId, setVideoId, movedSetVideoIdSuccessor).status.value
    }

    suspend fun editPlaylist(
        playlistId: String,
        title: String,
    ) = runCatching {
        ytMusic.editYouTubePlaylist(playlistId, title).status.value
    }

    suspend fun createPlaylist(
        title: String,
        listVideoId: List<String>?,
    ) = runCatching {
        ytMusic.createYouTubePlaylist(title, listVideoId).body<CreatePlaylistResponse>()
    }

    suspend fun subscribeChannel(channelId: String) =
        runCatching {
            ytMusic.subscribeChannel(channelId).status.value
        }

    suspend fun unsubscribeChannel(channelId: String) =
        runCatching {
            ytMusic.unsubscribeChannel(channelId).status.value
        }

    suspend fun addToLiked(mediaId: String) =
        runCatching {
            ytMusic.addToLiked(mediaId).status.value
        }

    suspend fun removeFromLiked(mediaId: String) =
        runCatching {
            ytMusic.removeFromLiked(mediaId).status.value
        }

    suspend fun dislike(mediaId: String) =
        runCatching {
            ytMusic.dislike(mediaId).status.value
        }

    suspend fun getSimpMusicChart() =
        runCatching {
            ytMusic.getSimpMusicChart().body<SimpMusicChartResponse>()
        }

    fun getExtractSource(videoId: String): String? = ExtractSource.of(videoId)

    fun download(
        track: SongItem,
        filePath: String,
        videoId: String,
        isVideo: Boolean = false,
    ): Flow<DownloadProgress> =
        channelFlow {
            trySend(DownloadProgress(0.01f))
            player(videoId = videoId)
                .onSuccess { playerResponse ->
                    val audioFormat =
                        listOf(
                            playerResponse.second.streamingData
                                ?.formats
                                ?.filter { it.isAudio && it.url != null }
                                ?.maxByOrNull { it.bitrate },
                            playerResponse.second.streamingData
                                ?.adaptiveFormats
                                ?.filter { it.isAudio && it.url != null }
                                ?.maxByOrNull { it.bitrate },
                        ).maxByOrNull { it?.bitrate ?: 0 }
                    val videoFormat =
                        listOf(
                            playerResponse.second.streamingData
                                ?.formats
                                ?.filter { !it.isAudio && it.url != null }
                                ?.maxByOrNull { it.bitrate },
                            playerResponse.second.streamingData
                                ?.adaptiveFormats
                                ?.filter { !it.isAudio && it.url != null }
                                ?.maxByOrNull { it.bitrate },
                        ).maxByOrNull { it?.bitrate ?: 0 }
                    Logger.d(TAG, "Audio Format $audioFormat")
                    Logger.d(TAG, "Video Format $videoFormat")
                    val audioUrl =
                        audioFormat?.url ?: run {
                            trySend(DownloadProgress.failed("Audio format url is null"))
                            return@channelFlow
                        }
                    if (isVideo) {
                        runCatching {
                            val videoUrl = videoFormat?.url ?: throw Exception("Video format url is null")
                            val downloadAudioJob = ytMusic.download(audioUrl, ("$filePath.webm"))
                            val downloadVideoJob = ytMusic.download(videoUrl, ("$filePath.mp4"))
                            combine(downloadVideoJob, downloadAudioJob) { videoProgress, audioProgress ->
                                Pair(videoProgress, audioProgress)
                            }.collectLatest { (videoProgress, audioProgress) ->
                                if (!videoProgress.first || !audioProgress.first) {
                                    trySend(
                                        DownloadProgress(
                                            videoDownloadProgress = videoProgress.second,
                                            audioDownloadProgress = audioProgress.second,
                                            downloadSpeed = if (videoProgress.third != 0) videoProgress.third else audioProgress.third,
                                        ),
                                    )
                                } else {
                                    trySend(DownloadProgress.MERGING)
                                    trySend(
                                        ytMusic.mergeAudioVideoDownload(
                                            filePath,
                                        ),
                                    )
                                }
                            }
                        }.onSuccess {
                            Logger.d(TAG, "Download Video Success")
                        }.onFailure {
                            it.printStackTrace()
                            trySend(DownloadProgress.failed(it.message ?: "Download failed"))
                        }
                    } else {
                        runCatching {
                            ytMusic
                                .download(audioUrl, ("$filePath.webm"))
                                .collect { downloadProgress ->
                                    if (!downloadProgress.first) {
                                        trySend(DownloadProgress(audioDownloadProgress = downloadProgress.second))
                                    } else {
                                        trySend(DownloadProgress(audioDownloadProgress = 1f, isDone = true))
                                    }
                                }
                        }.onSuccess {
                            Logger.d(TAG, "Download only Audio Success")
                            trySend(
                                ytMusic.saveAudioWithThumbnail(
                                    filePath,
                                    track,
                                ),
                            )
                        }.onFailure { e ->
                            e.printStackTrace()
                            trySend(DownloadProgress.failed(e.message ?: "Download failed"))
                        }
                    }
                }.onFailure {
                    it.printStackTrace()
                    Logger.d(TAG, "Player Response is null")
                    trySend(DownloadProgress.failed(it.message ?: "Player response is null"))
                }
        }.flowOn(Dispatchers.IO)

    suspend fun is403Url(url: String) = ytMusic.is403Url(url)

    companion object {
        const val MAX_GET_QUEUE_SIZE = 1000

        private val VISITOR_DATA_REGEX = Regex("^Cg[stuv][A-Za-z0-9_-]{15,}")

        const val DEFAULT_VISITOR_DATA = "CgtsZG1ySnZiQWtSbyiMjuGSBg%3D%3D"

    }
}

private fun List<Tabs.Tab>.tabEndpoint(pageType: String): BrowseEndpoint? =
    firstNotNullOfOrNull { tab ->
        tab.tabRenderer.endpoint?.browseEndpoint?.takeIf {
            it.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == pageType
        }
    }

internal fun MusicQueueRenderer.SubHeaderChipCloud?.toRadioChips(): List<YouTube.RadioChip> =
    this
        ?.chipCloudRenderer
        ?.chips
        ?.mapNotNull { chip ->
            val renderer = chip.chipCloudChipRenderer ?: return@mapNotNull null
            val title = renderer.text?.runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val watch = renderer.navigationEndpoint?.radioEndpoint
            YouTube.RadioChip(
                title = title,
                playlistId = watch?.playlistId,
                params = watch?.params,
                selected = renderer.isSelected == true,
            )
        }.orEmpty()
