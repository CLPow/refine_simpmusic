package com.maxrave.data.repository

import com.maxrave.domain.data.model.searchResult.SearchSummary
import com.maxrave.data.parser.search.parseSearchSummary
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toDomainSearchSuggestions
import com.maxrave.data.parser.parseEpisodeSearch
import com.maxrave.data.parser.parsePodcast
import com.maxrave.data.parser.search.parseSearchAlbum
import com.maxrave.data.parser.search.parseSearchArtist
import com.maxrave.data.parser.search.parseSearchPlaylist
import com.maxrave.data.parser.search.parseSearchSong
import com.maxrave.data.parser.search.parseSearchVideo
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.podcast.PodcastEpisode
import com.maxrave.domain.data.model.searchResult.SearchSuggestions
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class SearchRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : SearchRepository {
    override fun getSearchHistory(): Flow<List<SearchHistory>> =
        flow {
            emit(localDataSource.getSearchHistory())
        }.flowOn(Dispatchers.IO)

    override fun insertSearchHistory(searchHistory: SearchHistory): Flow<Long> =
        flow {
            emit(localDataSource.insertSearchHistory(searchHistory))
        }.flowOn(Dispatchers.IO)

    override suspend fun deleteSearchHistory() =
        withContext(Dispatchers.IO) {
            localDataSource.deleteSearchHistory()
        }

    override suspend fun deleteSearchHistoryItem(query: String) =
        withContext(Dispatchers.IO) {
            localDataSource.deleteSearchHistoryItem(query)
        }

    override fun getSearchDataSong(query: String): Flow<Resource<ArrayList<SongsResult>>> =
        flow {
            val first = youTube.search(query, YouTube.SearchFilter.FILTER_SONG)
            val result = first.getOrNull()
            if (result == null) {
                Logger.d("Search", "Error: ${first.exceptionOrNull()?.message}")
                emit(Resource.Error<ArrayList<SongsResult>>(first.exceptionOrNull()?.message.toString()))
                return@flow
            }
            val listSongs: ArrayList<SongsResult> = arrayListOf()
            listSongs.addAll(parseSearchSong(result).filter { MusicVideoType.isPlayableSong(it.videoType) })
            emit(Resource.Success<ArrayList<SongsResult>>(ArrayList(listSongs)))
            var continueParam = result.continuation
            var count = 0
            while (count < 2 && continueParam != null) {
                val values = youTube.searchContinuation(continueParam).getOrNull()
                if (values != null) listSongs.addAll(parseSearchSong(values))
                continueParam = values?.continuation
                count++
            }
            listSongs.retainAll { MusicVideoType.isPlayableSong(it.videoType) }
            emit(Resource.Success<ArrayList<SongsResult>>(listSongs))
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataVideo(query: String): Flow<Resource<ArrayList<VideosResult>>> =
        flow {
            runCatching {
                youTube
                    .search(query, YouTube.SearchFilter.FILTER_VIDEO)
                    .onSuccess { result ->
                        val listSongs: ArrayList<VideosResult> = arrayListOf()
                        var countinueParam = result.continuation
                        parseSearchVideo(result).let { list ->
                            listSongs.addAll(list)
                        }
                        emit(Resource.Success<ArrayList<VideosResult>>(ArrayList(listSongs)))
                        var count = 0
                        while (count < 2 && countinueParam != null) {
                            youTube
                                .searchContinuation(countinueParam)
                                .onSuccess { values ->
                                    parseSearchVideo(values).let { list ->
                                        listSongs.addAll(list)
                                    }
                                    count++
                                    countinueParam = values.continuation
                                }.onFailure {
                                    Logger.e("Continue", "Error: ${it.message}")
                                    countinueParam = null
                                    count++
                                }
                        }

                        emit(Resource.Success<ArrayList<VideosResult>>(listSongs))
                    }.onFailure { e ->
                        Logger.d("Search", "Error: ${e.message}")
                        emit(Resource.Error<ArrayList<VideosResult>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataFeaturedPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        flow {
            runCatching {
                youTube
                    .search(query, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
                    .onSuccess { result ->
                        val listPlaylist: ArrayList<PlaylistsResult> = arrayListOf()
                        var countinueParam = result.continuation
                        parseSearchPlaylist(result).let { list ->
                            listPlaylist.addAll(list)
                        }
                        emit(Resource.Success<ArrayList<PlaylistsResult>>(ArrayList(listPlaylist)))
                        var count = 0
                        while (count < 2 && countinueParam != null) {
                            youTube
                                .searchContinuation(countinueParam)
                                .onSuccess { values ->
                                    parseSearchPlaylist(values).let { list ->
                                        listPlaylist.addAll(list)
                                    }
                                    count++
                                    countinueParam = values.continuation
                                }.onFailure {
                                    Logger.e("Continue", "Error: ${it.message}")
                                    countinueParam = null
                                    count++
                                }
                        }
                        emit(Resource.Success<ArrayList<PlaylistsResult>>(listPlaylist))
                    }.onFailure { e ->
                        Logger.d("Search", "Error: ${e.message}")
                        emit(Resource.Error<ArrayList<PlaylistsResult>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataArtist(query: String): Flow<Resource<ArrayList<ArtistsResult>>> =
        flow {
            runCatching {
                youTube
                    .search(query, YouTube.SearchFilter.FILTER_ARTIST)
                    .onSuccess { result ->
                        val listArtist: ArrayList<ArtistsResult> = arrayListOf()
                        var countinueParam = result.continuation
                        parseSearchArtist(result).let { list ->
                            listArtist.addAll(list)
                        }
                        emit(Resource.Success<ArrayList<ArtistsResult>>(ArrayList(listArtist)))
                        var count = 0
                        while (count < 2 && countinueParam != null) {
                            youTube
                                .searchContinuation(countinueParam)
                                .onSuccess { values ->
                                    parseSearchArtist(values).let { list ->
                                        listArtist.addAll(list)
                                    }
                                    count++
                                    countinueParam = values.continuation
                                }.onFailure {
                                    Logger.e("Continue", "Error: ${it.message}")
                                    countinueParam = null
                                    count++
                                }
                        }
                        emit(Resource.Success<ArrayList<ArtistsResult>>(listArtist))
                    }.onFailure { e ->
                        Logger.d("Search", "Error: ${e.message}")
                        emit(Resource.Error<ArrayList<ArtistsResult>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataAlbum(query: String): Flow<Resource<ArrayList<AlbumsResult>>> =
        flow {
            runCatching {
                youTube
                    .search(query, YouTube.SearchFilter.FILTER_ALBUM)
                    .onSuccess { result ->
                        val listAlbum: ArrayList<AlbumsResult> = arrayListOf()
                        var countinueParam = result.continuation
                        parseSearchAlbum(result).let { list ->
                            listAlbum.addAll(list)
                        }
                        emit(Resource.Success<ArrayList<AlbumsResult>>(ArrayList(listAlbum)))
                        var count = 0
                        while (count < 2 && countinueParam != null) {
                            youTube
                                .searchContinuation(countinueParam)
                                .onSuccess { values ->
                                    parseSearchAlbum(values).let { list ->
                                        listAlbum.addAll(list)
                                    }
                                    count++
                                    countinueParam = values.continuation
                                }.onFailure {
                                    Logger.e("Continue", "Error: ${it.message}")
                                    countinueParam = null
                                    count++
                                }
                        }
                        emit(Resource.Success<ArrayList<AlbumsResult>>(listAlbum))
                    }.onFailure { e ->
                        Logger.d("Search", "Error: ${e.message}")
                        emit(Resource.Error<ArrayList<AlbumsResult>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        flow {
            runCatching {
                youTube
                    .search(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                    .onSuccess { result ->
                        val listPlaylist: ArrayList<PlaylistsResult> = arrayListOf()
                        var countinueParam = result.continuation
                        parseSearchPlaylist(result).let { list ->
                            listPlaylist.addAll(list)
                        }
                        var count = 0
                        while (count < 2 && countinueParam != null) {
                            youTube
                                .searchContinuation(countinueParam)
                                .onSuccess { values ->
                                    parseSearchPlaylist(values).let { list ->
                                        listPlaylist.addAll(list)
                                    }
                                    count++
                                    countinueParam = values.continuation
                                }.onFailure {
                                    Logger.e("Continue", "Error: ${it.message}")
                                    countinueParam = null
                                    count++
                                }
                        }
                        emit(Resource.Success<ArrayList<PlaylistsResult>>(listPlaylist))
                    }.onFailure { e ->
                        Logger.d("Search", "Error: ${e.message}")
                        emit(Resource.Error<ArrayList<PlaylistsResult>>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataPodcast(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        flow {
            val first = youTube.search(query, YouTube.SearchFilter.FILTER_PODCAST)
            val result = first.getOrNull()
            if (result == null) {
                emit(Resource.Error<ArrayList<PlaylistsResult>>(first.exceptionOrNull()?.message.toString()))
                return@flow
            }
            val list = ArrayList(parsePodcast(result.listPodcast))
            emit(Resource.Success(ArrayList(list)))
            var continueParam = result.continuation
            var count = 0
            while (count < 2 && continueParam != null) {
                val values = youTube.searchContinuation(continueParam).getOrNull()
                if (values != null) list.addAll(parsePodcast(values.listPodcast))
                continueParam = values?.continuation
                count++
            }
            emit(Resource.Success(ArrayList(list.distinctBy { it.browseId })))
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataEpisode(query: String): Flow<Resource<ArrayList<PodcastEpisode>>> =
        flow {
            val first = youTube.search(query, FILTER_EPISODE)
            val result = first.getOrNull()
            if (result == null) {
                emit(Resource.Error<ArrayList<PodcastEpisode>>(first.exceptionOrNull()?.message.toString()))
                return@flow
            }
            val list = ArrayList(parseEpisodeSearch(result))
            emit(Resource.Success(ArrayList(list)))
            var continueParam = result.continuation
            var count = 0
            while (count < 2 && continueParam != null) {
                val values = youTube.searchContinuation(continueParam).getOrNull()
                if (values != null) list.addAll(parseEpisodeSearch(values))
                continueParam = values?.continuation
                count++
            }
            emit(Resource.Success(ArrayList(list.distinctBy { it.videoId })))
        }.flowOn(Dispatchers.IO)

    override fun getSearchSummary(query: String): Flow<Resource<SearchSummary>> =
        flow {
            youTube
                .searchSummary(query)
                .onSuccess { emit(Resource.Success(parseSearchSummary(it))) }
                .onFailure { e ->
                    Logger.w("Search", "Search page failed: ${e.message}")
                    emit(Resource.Error<SearchSummary>(e.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getSuggestQuery(query: String): Flow<Resource<SearchSuggestions>> =
        flow {
            runCatching {
                youTube
                    .getYTMusicSearchSuggestions(query)
                    .onSuccess {
                        emit(Resource.Success(it.toDomainSearchSuggestions()))
                    }.onFailure { e ->
                        Logger.d("Suggest", "Error: ${e.message}")
                        emit(Resource.Error<SearchSuggestions>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    private companion object {
        val FILTER_EPISODE = YouTube.SearchFilter("EgWKAQJIAWoMEA4QChADEAQQCRAF")
    }
}
