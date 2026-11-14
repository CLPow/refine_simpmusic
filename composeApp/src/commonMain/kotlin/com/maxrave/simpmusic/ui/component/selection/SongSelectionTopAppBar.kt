package com.maxrave.simpmusic.ui.component.selection

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.SelectAll
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.more
import simpmusic.composeapp.generated.resources.exit_selection
import simpmusic.composeapp.generated.resources.n_selected
import simpmusic.composeapp.generated.resources.select_all

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSelectionTopAppBar(
    state: SongSelectionState,
    onSelectAll: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    contentColor: Color = Color.White,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = windowInsets,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                titleContentColor = contentColor,
                navigationIconContentColor = contentColor,
                actionIconContentColor = contentColor,
            ),
        navigationIcon = {
            IconButton(onClick = { state.exit() }) {
                Icon(
                    imageVector = SimpIcons.Close,
                    contentDescription = stringResource(Res.string.exit_selection),
                )
            }
        },
        title = {
            Text(
                text = stringResource(Res.string.n_selected, state.count),
                style = typo().titleMedium,
                color = contentColor,
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = SimpIcons.SelectAll,
                    contentDescription = stringResource(Res.string.select_all),
                )
            }
            if (state.count > 0) {
                IconButton(onClick = onOpenActions) {
                    Icon(
                        imageVector = SimpIcons.MoreVert,
                        contentDescription = stringResource(Res.string.more),
                    )
                }
            }
        },
    )
}
