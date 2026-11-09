package com.maxrave.simpmusic.extension

import androidx.compose.runtime.Composable
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.viewModel.ArtistScreenData
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.custom_order
import simpmusic.composeapp.generated.resources.na_na
import simpmusic.composeapp.generated.resources.newer_first
import simpmusic.composeapp.generated.resources.older_first
import simpmusic.composeapp.generated.resources.title
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun formatDuration(duration: Long): String {
    if (duration < 0L) return stringResource(Res.string.na_na)
    val minutes: Long = TimeUnit.MINUTES.convert(duration, TimeUnit.MILLISECONDS)
    val seconds: Long = (
        TimeUnit.SECONDS.convert(duration, TimeUnit.MILLISECONDS) -
            minutes * TimeUnit.SECONDS.convert(1, TimeUnit.MINUTES)
    )
    return String.format(Locale.ENGLISH, "%02d:%02d", minutes, seconds)
}

fun getSizeOfFile(dir: File): Long {
    var dirSize: Long = 0
    if (!dir.listFiles().isNullOrEmpty()) {
        for (f in dir.listFiles()!!) {
            dirSize += f.length()
            if (f.isDirectory) {
                dirSize += getSizeOfFile(f)
            }
        }
    }
    return dirSize
}

fun ArtistBrowse.toArtistScreenData(): ArtistScreenData =
    ArtistScreenData(
        title = this.name,
        imageUrl = this.thumbnails?.lastOrNull()?.url,
        subscribers = this.subscribers,
        playCount = this.views,
        isChannel = this.songs == null,
        channelId = this.channelId,
        radioParam = this.radioId,
        shuffleParam = this.shuffleId,
        description = this.description,
        listSongParam = this.songs?.browseId,
        popularSongs = this.songs?.results?.map { it.toTrack() } ?: emptyList(),
        singles = this.singles,
        albums = this.albums,
        video =
            this.video?.let { video ->
                ArtistBrowse.Videos(video.map { it.toTrack() }, this.videoList)
            },
        related = this.related,
        featuredOn = this.featuredOn ?: emptyList(),
    )

fun FilterState.displayNameRes(): StringResource =
    when (this) {
        FilterState.NewerFirst -> Res.string.newer_first
        FilterState.OlderFirst -> Res.string.older_first
        FilterState.Title -> Res.string.title
        FilterState.CustomOrder -> Res.string.custom_order
    }

