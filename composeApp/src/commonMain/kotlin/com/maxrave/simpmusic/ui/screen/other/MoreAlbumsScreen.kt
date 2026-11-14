package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.simpmusic.ui.component.ytm.Thumb
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.MoreAlbumsDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.MoreAlbumsUIState
import com.maxrave.simpmusic.viewModel.MoreAlbumsViewModel
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.lazy.grid.itemsIndexed
import com.maxrave.simpmusic.ui.component.ytm.YtmErrorPage
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.ytm_singles_eps

@Composable
fun MoreAlbumsScreen(
    innerPadding: PaddingValues,
    navController: NavController,
    id: String? = null,
    type: String? = null,
    viewModel: MoreAlbumsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(id, type) {
        if (id == null) return@LaunchedEffect
        when (type) {
            MoreAlbumsDestination.SINGLE_TYPE -> viewModel.getSingleMore(id)
            else -> viewModel.getAlbumMore(id)
        }
    }
    val title =
        (uiState as? MoreAlbumsUIState.Success)?.title
            ?: stringResource(if (type == MoreAlbumsDestination.SINGLE_TYPE) Res.string.ytm_singles_eps else Res.string.albums)

    Column(Modifier.fillMaxSize().background(Ytm.Background)) {
        YtmTopBar(navController = navController, title = title, onBack = { navController.navigateUp() })
        when (val state = uiState) {
            is MoreAlbumsUIState.Success ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.albumItems, key = { i, it -> "$i-" + it.browseId }) { _, album ->
                        Column(Modifier.clickable { navController.navigate(AlbumDestination(browseId = album.browseId)) }) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                Thumb(album.thumbnails.lastOrNull()?.url, maxWidth, RoundedCornerShape(4.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                album.title,
                                style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(album.type, album.year).filter { it.isNotBlank() }.joinToString(" • "),
                                style = typo().bodyMedium,
                                color = Ytm.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            is MoreAlbumsUIState.Error ->
                YtmErrorPage(onBack = null) {
                    if (id != null) {
                        if (type == MoreAlbumsDestination.SINGLE_TYPE) viewModel.getSingleMore(id) else viewModel.getAlbumMore(id)
                    }
                }
            MoreAlbumsUIState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
    }
}
