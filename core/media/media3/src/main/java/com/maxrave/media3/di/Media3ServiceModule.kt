package com.maxrave.media3.di

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.maxrave.domain.repository.AlbumRepository
import android.app.Activity
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.maxrave.media3.cache.DEFAULT_SONG_CACHE_BYTES
import com.maxrave.media3.cache.SongCacheEvictor
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.cast.CastHandoffManager
import com.maxrave.media3.cast.CastStreamResolver
import com.maxrave.media3.exoplayer.ExoPlayerAdapter
import com.maxrave.media3.extension.isFullyCached
import com.maxrave.media3.repository.CacheRepositoryImpl
import com.maxrave.media3.service.SimpleMediaService
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.service.download.DownloadUtils
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import com.maxrave.domain.mediaservice.RenditionController
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.simpmusic.cast.initCast
import org.simpmusic.cast.wrapWithCastPlayer
import kotlin.time.Duration.Companion.seconds

@UnstableApi
private val mediaServiceModule =
    module {
        single<CoroutineScope>(
            createdAtStart = true,
            qualifier = named(SERVICE_SCOPE),
        ) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        single<DatabaseProvider>(
            createdAtStart = true,
        ) {
            provideDatabaseProvider(androidContext())
        }
        single<SimpleCache>(qualifier = named(PLAYER_CACHE), createdAtStart = true) {
            val configuredMb = runBlocking { get<DataStoreManager>().maxSongCacheSize.first() }
            SimpleCache(
                androidContext().filesDir.resolve("exoplayer"),
                SongCacheEvictor(if (configuredMb > 0) configuredMb * 1024L * 1024L else DEFAULT_SONG_CACHE_BYTES),
                get<DatabaseProvider>(),
            )
        }
        single<SimpleCache>(qualifier = named(DOWNLOAD_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "download",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        single<DownloadHandler>(createdAtStart = true) {
            DownloadUtils(
                context = androidContext(),
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                dataStoreManager = get(),
                databaseProvider = get(),
                streamRepository = get(),
                songRepository = get(),
            )
        }

        single<AudioAttributes>(createdAtStart = true) {
            provideAudioAttributes()
        }

        single<MergingMediaSourceFactory>(createdAtStart = true) {
            provideMergingMediaSource(
                androidContext(),
                get(named(DOWNLOAD_CACHE)),
                get(named(PLAYER_CACHE)),
                get(),
                get(named(SERVICE_SCOPE)),
                get(),
                get(),
            )
        }

        single<Player>(qualifier = named(MAIN_PLAYER)) {
            val adapter = get<MediaPlayerInterface>() as ExoPlayerAdapter
            initCast(androidContext())
            wrapWithCastPlayer(androidContext(), adapter.forwardingPlayer)
        }

        single<CoilBitmapLoader>(createdAtStart = true) {
            provideCoilBitmapLoader(androidContext(), get(named(SERVICE_SCOPE)))
        }

        single<MediaPlayerInterface>(createdAtStart = true) {
            ExoPlayerAdapter(
                context = androidContext(),
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                mediaSourceFactory = get(),
                audioAttributes = get(),
                streamRepository = get(),
                renditions = get(),
            )
        }

        single<CastHandoffManager>(createdAtStart = true) {
            CastHandoffManager(
                adapter = get<MediaPlayerInterface>() as ExoPlayerAdapter,
                sessionPlayer = get(qualifier = named(MAIN_PLAYER)),
                resolver = CastStreamResolver(get(), get()),
                coroutineScope = get(qualifier = named(SERVICE_SCOPE)),
            ).also { it.start() }
        }

        single<MediaLibrarySession.Callback>(createdAtStart = true) {
            SimpleMediaSessionCallback(
                androidApplication(),
                get<CoroutineScope>(named(SERVICE_SCOPE)),
                get<MediaPlayerHandler>(named(com.maxrave.common.Config.LOCAL_PLAYER)),
                get<SearchRepository>(),
                get<SongRepository>(),
                get<LocalPlaylistRepository>(),
                get<PlaylistRepository>(),
                get<HomeRepository>(),
                get<ArtistRepository>(),
                get<StreamRepository>(),
                get<AlbumRepository>(),
                get<DataStoreManager>(),
            )
        }

        single<CacheRepository>(createdAtStart = true) {
            CacheRepositoryImpl(
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
            )
        }
    }

@UnstableApi
private fun provideResolvingDataSourceFactory(
    context: Context,
    cacheDataSourceFactory: CacheDataSource.Factory,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    dataStoreManager: DataStoreManager,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
): DataSource.Factory {
    val chunkLength = 10 * 512 * 1024L
    val streamOverWifiOnly = dataStoreManager.streamOverWifiOnly.stateIn(coroutineScope, SharingStarted.Eagerly, false)
    val streamingIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    val upgradedIds = java.util.Collections.synchronizedSet(HashSet<String>())
    return ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
        val mediaId = (dataSpec.key ?: error("No media id")).removePrefix(MERGING_DATA_TYPE.VIDEO)
        if (dataSpec.position == 0L) streamingIds.remove(mediaId)
        if (downloadCache.isFullyCached(mediaId, dataSpec.position)) {
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) { streamRepository.updateFormat(mediaId) }
            }
            return@Factory dataSpec.subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        // Never on mobile data, and never mid-song.
        if (dataSpec.position == 0L &&
            context.hasUnmeteredNetwork() &&
            mediaId !in upgradedIds &&
            playerCache.getCachedSpans(mediaId).isNotEmpty()
        ) {
            val lowerQuality =
                runBlocking(Dispatchers.IO) {
                    val storedItag = streamRepository.getNewFormat(mediaId).lastOrNull()?.itag
                    storedItag != null && streamRepository.isLowerQuality(storedItag, streamRepository.wantedAudioItag(dataStoreManager))
                }
            if (lowerQuality) {
                Logger.w("Stream", "Cached copy of $mediaId is below the wanted quality; fetching it again")
                upgradedIds.add(mediaId)
                playerCache.removeResource(mediaId)
            }
        }
        if (playerCache.isFullyCached(mediaId, dataSpec.position)) {
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) { streamRepository.updateFormat(mediaId) }
            }
            return@Factory dataSpec.subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        if (streamOverWifiOnly.value && context.isActiveNetworkMetered() && (dataSpec.position == 0L || mediaId !in streamingIds)) {
            throw WifiOnlyException(mediaId)
        }
        synchronized(streamingIds) {
            streamingIds.add(mediaId)
            while (streamingIds.size > STREAMING_IDS_KEPT) streamingIds.remove(streamingIds.first())
        }
        var dataSpecReturn: DataSpec = dataSpec
        var resolved = false
        // Runs on ExoPlayer's loader thread, never Main, so blocking here is the intended shape.
        runBlocking(Dispatchers.IO) {
            val stored = streamRepository.getNewFormat(mediaId).lastOrNull()
            val hasCachedBytes = playerCache.getCachedSpans(mediaId).isNotEmpty()
            val keptItag = stored?.itag?.takeIf { hasCachedBytes }
            if (hasCachedBytes && keptItag == null) playerCache.removeResource(mediaId)
            val networkChanged =
                !hasCachedBytes &&
                    stored != null &&
                    !streamRepository.deliversQuality(stored.itag, streamRepository.wantedAudioItag(dataStoreManager))
            stored?.takeUnless { networkChanged }?.let {
                val audioUrl = it.audioUrl
                if (audioUrl != null && it.expiredTime > now()) {
                    val fresh = it.expiredTime > now().plusSeconds(FRESH_URL_MARGIN_S)
                    val is403Url = !fresh && streamRepository.is403Url(audioUrl).firstOrNull() != false
                    if (!is403Url) {
                        dataSpecReturn = dataSpec.withUri(audioUrl.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                        resolved = true
                        return@runBlocking
                    }
                }
            }
            streamRepository
                .getStream(
                    dataStoreManager,
                    mediaId,
                    isDownloading = false,
                    isVideo = false,
                    audioItag = keptItag,
                ).lastOrNull()
                ?.let {
                    dataSpecReturn = dataSpec.withUri(it.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                    resolved = true
                }
        }
        if (!resolved) {
            Logger.e("Stream", "Failed to resolve stream URL for $mediaId")
            if (streamRepository.isPremiumOnly(mediaId)) throw PremiumOnlyException(mediaId)
            throw java.io.IOException("Failed to resolve stream URL for $mediaId")
        }
        return@Factory dataSpecReturn
    }
}

@UnstableApi
private fun provideExtractorFactory(): ExtractorsFactory =
    ExtractorsFactory {
        arrayOf(
            FlacExtractor(
                FlacExtractor.FLAG_DISABLE_ID3_METADATA,
            ),
            MatroskaExtractor(
                DefaultSubtitleParserFactory(),
            ),
            FragmentedMp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
            Mp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
        )
    }

@UnstableApi
private fun provideMediaSourceFactory(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    dataStoreManager: DataStoreManager,
    coroutineScope: CoroutineScope,
): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(
        provideResolvingDataSourceFactory(
            context,
            provideCacheDataSource(
                downloadCache,
                playerCache,
                context,
            ),
            downloadCache,
            playerCache,
            dataStoreManager,
            streamRepository,
            coroutineScope,
        ),
        provideExtractorFactory(),
    )

@OptIn(UnstableApi::class)
private fun provideMergingMediaSource(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
    dataStoreManager: DataStoreManager,
    renditions: RenditionController,
): MergingMediaSourceFactory =
    MergingMediaSourceFactory(
        provideMediaSourceFactory(
            context,
            downloadCache,
            playerCache,
            streamRepository,
            dataStoreManager,
            coroutineScope,
        ),
        DefaultMediaSourceFactory(
            provideVideoDataSourceFactory(context, streamRepository, dataStoreManager),
            provideExtractorFactory(),
        ),
        renditions,
    )

private class VideoUrl(
    val url: String,
    val fetchedAtMs: Long,
)

@UnstableApi
private fun provideVideoDataSourceFactory(
    context: Context,
    streamRepository: StreamRepository,
    dataStoreManager: DataStoreManager,
): DataSource.Factory {
    val chunkLength = 10 * 512 * 1024L
    val urls = java.util.concurrent.ConcurrentHashMap<String, VideoUrl>()
    val upstream =
        DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(
                OkHttpClient
                    .Builder()
                    .connectTimeout(30.seconds)
                    .readTimeout(30.seconds)
                    .build(),
            ),
        )
    return ResolvingDataSource.Factory(upstream) { dataSpec ->
        val videoId = (dataSpec.key ?: error("No media id")).removePrefix(MERGING_DATA_TYPE.VIDEO)
        val nowMs = System.currentTimeMillis()
        val known = urls[videoId]?.takeIf { dataSpec.position > 0L || nowMs - it.fetchedAtMs < VIDEO_URL_TTL_MS }
        val url =
            known?.url ?: runBlocking(Dispatchers.IO) {
                streamRepository
                    .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = true)
                    .lastOrNull()
            }?.also { urls[videoId] = VideoUrl(it, nowMs) }
                ?: throw java.io.IOException("Failed to resolve video stream for $videoId")
        dataSpec.withUri(url.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
    }
}

private const val VIDEO_URL_TTL_MS = 3 * 3600 * 1000L

@UnstableApi
private fun provideCacheDataSource(
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    context: Context,
): CacheDataSource.Factory =
    CacheDataSource
        .Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    DefaultDataSource
                        .Factory(
                            context,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .connectTimeout(30.seconds)
                                    .readTimeout(30.seconds)
                                    .build(),
                            ),
                        ),
                ),
        ).setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

private const val FRESH_URL_MARGIN_S = 3 * 3600L

@UnstableApi
private fun provideAudioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

@UnstableApi
private fun provideDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)

@UnstableApi
private fun provideSimpleCache(
    context: Context,
    cacheName: String,
    cacheSize: Int = -1,
    databaseProvider: DatabaseProvider,
) = SimpleCache(
    context.filesDir.resolve(cacheName),
    when (cacheSize) {
        -1 -> NoOpCacheEvictor()
        else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
    },
    databaseProvider,
)

@UnstableApi
private fun provideCoilBitmapLoader(
    context: Context,
    coroutineScope: CoroutineScope,
): CoilBitmapLoader = CoilBitmapLoader(context, coroutineScope)

@OptIn(UnstableApi::class)
fun loadMediaService() {
    loadKoinModules(mediaServiceModule)
}

@OptIn(UnstableApi::class)
fun startService(
    context: Context,
    serviceConnection: ServiceConnection,
) {
    val intent = Intent(context, SimpleMediaService::class.java)
    try {
        context.startService(intent)
    } catch (e: IllegalStateException) {
        ContextCompat.startForegroundService(context, intent)
    }
    context.bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    Logger.d("Service", "Service started")
}

@OptIn(UnstableApi::class)
fun stopService(context: Context) {
    context.stopService(Intent(context, SimpleMediaService::class.java))
}

@OptIn(UnstableApi::class)
fun setServiceActivitySession(
    context: Context,
    cls: Class<out Activity>,
    musicService: IBinder?,
) {
    (musicService as? SimpleMediaService.MusicBinder)?.setActivitySession(context, cls)
}

private fun Context.hasUnmeteredNetwork(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
    return manager.activeNetwork != null && !manager.isActiveNetworkMetered
}

private fun Context.isActiveNetworkMetered(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
    return manager.activeNetwork != null && manager.isActiveNetworkMetered
}

private const val STREAMING_IDS_KEPT = 8

class WifiOnlyException(
    mediaId: String,
) : java.io.FileNotFoundException("Stream via Wi-Fi only: not streaming $mediaId on a metered network")

class PremiumOnlyException(
    mediaId: String,
) : java.io.IOException("$mediaId is only available to YouTube Music Premium members")
