package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.component.ytm.YtmChips
import com.maxrave.simpmusic.ui.component.ytm.YtmOutlinedPill
import com.maxrave.simpmusic.ui.component.ytm.YtmShelf
import com.maxrave.simpmusic.ui.component.ytm.YtmTopBar
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LoginDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.HomeViewModel
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_COMMUTE
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_ENERGIZE
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_FEEL_GOOD
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_FOCUS
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_PARTY
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_RELAX
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_ROMANCE
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_SAD
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_SLEEP
import com.maxrave.simpmusic.viewModel.HomeViewModel.Companion.HOME_PARAMS_WORKOUT
import com.maxrave.simpmusic.viewModel.ListState
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.commute
import simpmusic.composeapp.generated.resources.energize
import simpmusic.composeapp.generated.resources.feel_good
import simpmusic.composeapp.generated.resources.focus
import simpmusic.composeapp.generated.resources.party
import simpmusic.composeapp.generated.resources.relax
import simpmusic.composeapp.generated.resources.romance
import simpmusic.composeapp.generated.resources.sad
import simpmusic.composeapp.generated.resources.sleep
import simpmusic.composeapp.generated.resources.workout
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import com.maxrave.simpmusic.ui.icon.CloudOff
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.ytm_go_to_downloads
import simpmusic.composeapp.generated.resources.ytm_home_failed
import simpmusic.composeapp.generated.resources.ytm_load_more_failed
import simpmusic.composeapp.generated.resources.ytm_not_now
import simpmusic.composeapp.generated.resources.ytm_offline_body
import simpmusic.composeapp.generated.resources.ytm_offline_title
import simpmusic.composeapp.generated.resources.ytm_sign_in
import simpmusic.composeapp.generated.resources.ytm_sign_in_body
import simpmusic.composeapp.generated.resources.ytm_sign_in_title
import simpmusic.composeapp.generated.resources.ytm_signed_out_body
import simpmusic.composeapp.generated.resources.ytm_signed_out_title

/**
 * Chips shown until YouTube's own arrive with the first page. The feed's chips are what YouTube
 * Music shows, in its order; these are only the stand-in for the first frame.
 */
private val fallbackChips: List<Pair<StringResource, String>> =
    listOf(
        Res.string.energize to HOME_PARAMS_ENERGIZE,
        Res.string.relax to HOME_PARAMS_RELAX,
        Res.string.workout to HOME_PARAMS_WORKOUT,
        Res.string.feel_good to HOME_PARAMS_FEEL_GOOD,
        Res.string.commute to HOME_PARAMS_COMMUTE,
        Res.string.party to HOME_PARAMS_PARTY,
        Res.string.romance to HOME_PARAMS_ROMANCE,
        Res.string.sad to HOME_PARAMS_SAD,
        Res.string.focus to HOME_PARAMS_FOCUS,
        Res.string.sleep to HOME_PARAMS_SLEEP,
    )

/**
 * YouTube Music's Home: the logo bar, YouTube's chips, then YouTube's own feed for the account,
 * shelf after shelf in the order it sent them (Speed dial as its grid of tiles, Quick picks as its
 * song grid, video shelves as 16:9 cards, everything else as square cards), loading more as it
 * scrolls. The feed reloads when you come back to the app after a while, as YouTube Music's does,
 * so what you have been listening to shows up in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onScrolling: (onTop: Boolean) -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
    sharedViewModel: SharedViewModel = koinInject(),
    navController: NavController,
) {
    val listState = rememberLazyListState()
    val homeData by viewModel.homeItemList.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val failed by viewModel.recommendationsFailed.collectAsStateWithLifecycle()
    val params by viewModel.params.collectAsStateWithLifecycle()
    val homeListState by viewModel.homeListState.collectAsStateWithLifecycle()
    val serverChips by viewModel.homeChips.collectAsStateWithLifecycle()
    val continuation by viewModel.continuation.collectAsStateWithLifecycle()
    val reloadDestination by sharedViewModel.reloadDestination.collectAsStateWithLifecycle()
    val shouldShowLogInAlert by viewModel.showLogInAlert.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val signInExpired by viewModel.signInExpired.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.scrollToTop.collect { listState.scrollToItem(0) } }

    // Coming back to the app (or to this tab) after a while reloads the feed.
    LifecycleStartEffect(Unit) {
        viewModel.refreshIfStale()
        onStopOrDispose { }
    }
    LaunchedEffect(loading) { if (!loading) refreshing = false }

    // Tapping Home again: back to the top, or a reload when already there.
    LaunchedEffect(reloadDestination) {
        if (reloadDestination == HomeDestination::class) {
            if (listState.firstVisibleItemIndex > 0) {
                listState.animateScrollToItem(0)
            } else {
                refreshing = true
                viewModel.getHomeItemList(params)
            }
            sharedViewModel.reloadDestinationDone()
        }
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, homeListState) {
        if (nearEnd && homeListState == ListState.IDLE && !continuation.isNullOrEmpty()) {
            viewModel.getContinueHomeItem(continuation)
        }
    }

    if (shouldShowLogInAlert) {
        AlertDialog(
            containerColor = Ytm.Surface,
            title = { Text(stringResource(Res.string.ytm_sign_in_title), color = Color.White) },
            text = {
                Text(
                    stringResource(Res.string.ytm_sign_in_body),
                    color = Ytm.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.doneShowLogInAlert(true)
                    navController.navigate(LoginDestination)
                }) { Text(stringResource(Res.string.ytm_sign_in), color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.doneShowLogInAlert(true) }) { Text(stringResource(Res.string.ytm_not_now), color = Ytm.TextSecondary) }
            },
            onDismissRequest = { viewModel.doneShowLogInAlert() },
        )
    }

    // YouTube Music's Home sits on a dark slate wash that fades into the page from the top.
    Column(
        Modifier
            .fillMaxSize()
            .background(Ytm.Background)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color(0xFF1C2B36),
                    0.45f to Ytm.Background,
                ),
            ),
    ) {
        YtmTopBar(navController = navController)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                viewModel.getHomeItemList(params)
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "chips") {
                    // Tapping the selected chip clears it, back to the whole feed.
                    val chips: List<Pair<String, String>> =
                        if (serverChips.isNotEmpty()) {
                            serverChips.map { it.title to it.params }
                        } else {
                            fallbackChips.map { stringResource(it.first) to it.second }
                        }
                    val selected = chips.indexOfFirst { it.second == params }.takeIf { it >= 0 }
                    YtmChips(
                        labels = chips.map { it.first },
                        selected = selected,
                        onSelect = { index ->
                            viewModel.setParams(if (index == selected) null else chips[index].second)
                        },
                        modifier = Modifier,
                    )
                }
                if (signInExpired) {
                    item(key = "signed-out") {
                        HomeNotice(
                            title = stringResource(Res.string.ytm_signed_out_title),
                            body = stringResource(Res.string.ytm_signed_out_body),
                            action = stringResource(Res.string.ytm_sign_in),
                        ) { navController.navigate(LoginDestination) }
                    }
                }
                if (homeData.isEmpty()) {
                    item(key = "state") {
                        Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                            if (offline && !loading) {
                                Column(
                                    Modifier.padding(horizontal = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(SimpIcons.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                                    Text(
                                        stringResource(Res.string.ytm_offline_title),
                                        style = typo().titleMedium,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        stringResource(Res.string.ytm_offline_body),
                                        style = typo().bodyMedium,
                                        color = Ytm.TextSecondary,
                                        textAlign = TextAlign.Center,
                                    )
                                    YtmOutlinedPill(stringResource(Res.string.ytm_go_to_downloads)) {
                                        navController.navigate(LibraryDynamicPlaylistDestination(type = "downloaded"))
                                    }
                                    YtmOutlinedPill(stringResource(Res.string.retry)) { viewModel.getHomeItemList(params) }
                                }
                            } else if (failed && !loading) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        stringResource(Res.string.ytm_home_failed),
                                        style = typo().bodyLarge,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                    )
                                    YtmOutlinedPill(stringResource(Res.string.retry)) { viewModel.getHomeItemList(params) }
                                }
                            } else {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
                itemsIndexed(homeData, key = { i, it -> "$i|" + it.title + it.subtitle.orEmpty() + it.isSpeedDial }) { _, shelf ->
                    YtmShelf(item = shelf, navController = navController)
                }
                if (homeListState == ListState.PAGINATING) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                if (homeListState == ListState.ERROR && homeData.isNotEmpty()) {
                    item(key = "more-failed") {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(Res.string.ytm_load_more_failed), style = typo().bodyMedium, color = Ytm.TextSecondary)
                            YtmOutlinedPill(stringResource(Res.string.retry)) { viewModel.getContinueHomeItem(continuation) }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/** A notice card at the top of Home, such as an expired sign-in. */
@Composable
private fun HomeNotice(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Ytm.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = typo().titleMedium, color = Color.White)
        Text(body, style = typo().bodyMedium, color = Ytm.TextSecondary)
        YtmOutlinedPill(action, onClick = onAction)
    }
}
