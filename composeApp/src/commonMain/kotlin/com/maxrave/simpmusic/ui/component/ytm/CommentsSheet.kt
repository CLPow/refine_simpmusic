package com.maxrave.simpmusic.ui.component.ytm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.maxrave.domain.data.model.comments.CommentLikeState
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsHeader
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.ui.icon.Send
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.ThumbDown
import com.maxrave.simpmusic.ui.icon.ThumbUp
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ytm_comments
import simpmusic.composeapp.generated.resources.ytm_comments_failed
import simpmusic.composeapp.generated.resources.ytm_comments_none
import simpmusic.composeapp.generated.resources.ytm_comments_reply
import simpmusic.composeapp.generated.resources.ytm_comments_replying_to
import simpmusic.composeapp.generated.resources.ytm_comments_sign_in
import simpmusic.composeapp.generated.resources.ytm_comments_placeholder
import simpmusic.composeapp.generated.resources.ytm_comments_pinned
import simpmusic.composeapp.generated.resources.retry

/** YouTube's blue for "N replies" and the other links in the comments. */
private val LinkBlue = Color(0xFF3EA6FF)

/** A thread's replies as far as they are loaded. */
data class ReplyThread(
    val replies: List<YouTubeComment> = emptyList(),
    val nextToken: String? = null,
    val nextLabel: String? = null,
    val loading: Boolean = false,
)

/**
 * The comments of one track (YouTube Music's Comments under the player). The first page is
 * loaded as soon as the player shows the track, for the count on the pill.
 */
@Stable
class CommentsState(
    private val repository: SongRepository,
    private val scope: CoroutineScope,
    val videoId: String,
) {
    var header by mutableStateOf<YouTubeCommentsHeader?>(null)
        private set
    val comments = mutableStateListOf<YouTubeComment>()
    var nextToken by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Comments turned off (or a live stream with only its chat). */
    var turnedOff by mutableStateOf(false)
        private set
    val threads = mutableStateMapOf<String, ReplyThread>()
    var posting by mutableStateOf(false)
        private set

    /** Signed in with a channel: YouTube sent a comment box. */
    val canWrite get() = header?.createParams != null

    fun load() {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            when (val res = repository.getComments(videoId).lastOrNull()) {
                is Resource.Success -> {
                    val page = res.data
                    if (page == null) {
                        turnedOff = true
                    } else {
                        header = page.header
                        comments.clear()
                        comments.addAll(page.comments)
                        nextToken = page.nextToken
                    }
                }
                else -> failed = true
            }
            loading = false
        }
    }

    fun loadMore() {
        val token = nextToken ?: return
        if (loading) return
        loading = true
        scope.launch {
            val page = repository.getCommentsPage(token).lastOrNull()?.data
            if (page != null) {
                comments.addAll(page.comments.filter { new -> comments.none { it.id == new.id } })
                nextToken = page.nextToken
            }
            loading = false
        }
    }

    fun toggleReplies(comment: YouTubeComment) {
        if (threads.containsKey(comment.id)) {
            threads.remove(comment.id)
            return
        }
        val token = comment.repliesToken ?: return
        threads[comment.id] = ReplyThread(loading = true)
        loadReplies(comment.id, token, append = false)
    }

    fun moreReplies(comment: YouTubeComment) {
        val thread = threads[comment.id] ?: return
        val token = thread.nextToken ?: return
        threads[comment.id] = thread.copy(loading = true)
        loadReplies(comment.id, token, append = true)
    }

    private fun loadReplies(
        id: String,
        token: String,
        append: Boolean,
    ) {
        scope.launch {
            val page = repository.getCommentsPage(token).lastOrNull()?.data
            val before = threads[id]?.replies.orEmpty()
            threads[id] =
                ReplyThread(
                    replies = if (append) before + page?.comments.orEmpty() else page?.comments.orEmpty(),
                    nextToken = page?.nextToken,
                    nextLabel = page?.nextLabel,
                )
        }
    }

    /** Thumbs up, or takes it back. Shown at once; YouTube is told in the background. */
    fun like(comment: YouTubeComment) {
        val liked = comment.likeState == CommentLikeState.LIKED
        val action = (if (liked) comment.unlikeAction else comment.likeAction) ?: return
        replace(comment.copy(likeState = if (liked) CommentLikeState.NONE else CommentLikeState.LIKED))
        scope.launch { repository.performCommentAction(action).lastOrNull() }
    }

    fun dislike(comment: YouTubeComment) {
        val disliked = comment.likeState == CommentLikeState.DISLIKED
        val action = (if (disliked) comment.undislikeAction else comment.dislikeAction) ?: return
        replace(comment.copy(likeState = if (disliked) CommentLikeState.NONE else CommentLikeState.DISLIKED))
        scope.launch { repository.performCommentAction(action).lastOrNull() }
    }

    private fun replace(updated: YouTubeComment) {
        val top = comments.indexOfFirst { it.id == updated.id }
        if (top >= 0) {
            comments[top] = updated
            return
        }
        threads.entries.firstOrNull { (_, thread) -> thread.replies.any { it.id == updated.id } }?.let { (id, thread) ->
            threads[id] = thread.copy(replies = thread.replies.map { if (it.id == updated.id) updated else it })
        }
    }

    /** Posts [text] as a comment, or as a reply to [replyTo]. True when YouTube took it. */
    suspend fun post(
        text: String,
        replyTo: YouTubeComment?,
    ): Boolean {
        posting = true
        try {
            if (replyTo != null) {
                val params = replyTo.replyParams ?: return false
                val res = repository.postCommentReply(params, text).lastOrNull()
                if (res !is Resource.Success) return false
                val thread = threads[replyTo.id] ?: ReplyThread()
                res.data?.let { threads[replyTo.id] = thread.copy(replies = listOf(it) + thread.replies) }
            } else {
                val params = header?.createParams ?: return false
                val res = repository.postComment(params, text).lastOrNull()
                if (res !is Resource.Success) return false
                res.data?.let { comments.add(0, it) }
            }
            return true
        } finally {
            posting = false
        }
    }
}

@Composable
fun rememberCommentsState(videoId: String): CommentsState {
    val repository: SongRepository = koinInject()
    val scope = rememberCoroutineScope()
    val state = remember(videoId) { CommentsState(repository, scope, videoId) }
    LaunchedEffect(state) { state.load() }
    return state
}

/** YouTube Music's comments sheet: the thread list, replies, and the comment box when signed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    state: CommentsState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var replyTo by remember { mutableStateOf<YouTubeComment?>(null) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) state.loadMore() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ytm.Surface,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = .5f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(stringResource(Res.string.ytm_comments), style = typo().titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                state.header?.countText?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = typo().bodyMedium, color = Ytm.TextSecondary)
                }
            }
            HorizontalDivider(color = Ytm.Divider)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.turnedOff -> CenterNote(stringResource(Res.string.ytm_comments_none))
                    state.failed && state.comments.isEmpty() ->
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(Res.string.ytm_comments_failed), color = Ytm.TextSecondary)
                            Text(
                                stringResource(Res.string.retry),
                                color = LinkBlue,
                                modifier = Modifier.clickable { state.load() }.padding(12.dp),
                            )
                        }
                    state.comments.isEmpty() && state.loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    else ->
                        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(state.comments, key = { it.id }) { comment ->
                                CommentRow(comment, state, onReply = { replyTo = it })
                                val thread = state.threads[comment.id]
                                if (thread != null) {
                                    thread.replies.forEach { reply ->
                                        Box(Modifier.padding(start = 44.dp)) { CommentRow(reply, state, onReply = { replyTo = comment }) }
                                    }
                                    when {
                                        thread.loading ->
                                            CircularProgressIndicator(
                                                Modifier.padding(start = 60.dp, top = 4.dp, bottom = 8.dp).size(18.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp,
                                            )
                                        thread.nextToken != null ->
                                            Text(
                                                thread.nextLabel ?: "…",
                                                color = LinkBlue,
                                                style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                modifier = Modifier.padding(start = 60.dp).clickable { state.moreReplies(comment) }.padding(8.dp),
                                            )
                                    }
                                }
                            }
                            if (state.loading) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                }
            }

            HorizontalDivider(color = Ytm.Divider)
            if (state.canWrite) {
                replyTo?.let { target ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.ytm_comments_replying_to, target.author),
                            style = typo().bodySmall,
                            color = Ytm.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text("✕", color = Ytm.TextSecondary, modifier = Modifier.clickable { replyTo = null }.padding(6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).windowInsetsPadding(WindowInsets.navigationBars),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(state.header?.userAvatar, 32)
                    Spacer(Modifier.width(8.dp))
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = {
                            Text(
                                replyTo?.replyPlaceholder ?: state.header?.placeholder ?: stringResource(Res.string.ytm_comments_placeholder),
                                color = Ytm.TextSecondary,
                            )
                        },
                        maxLines = 4,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedIndicatorColor = Color.White,
                                unfocusedIndicatorColor = Ytm.Divider,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = draft.isNotBlank() && !state.posting,
                        onClick = {
                            val text = draft.trim()
                            val target = replyTo
                            scope.launch {
                                if (state.post(text, target)) {
                                    draft = ""
                                    replyTo = null
                                }
                            }
                        },
                    ) {
                        Icon(SimpIcons.Send, contentDescription = null, tint = if (draft.isNotBlank()) Color.White else Ytm.TextSecondary)
                    }
                }
            } else if (!state.turnedOff) {
                Text(
                    stringResource(Res.string.ytm_comments_sign_in),
                    style = typo().bodySmall,
                    color = Ytm.TextSecondary,
                    modifier = Modifier.padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: YouTubeComment,
    state: CommentsState,
    onReply: (YouTubeComment) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Avatar(comment.authorAvatar, 32)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (comment.isPinned) {
                Text(stringResource(Res.string.ytm_comments_pinned), style = typo().bodySmall.copy(fontSize = 11.sp), color = Ytm.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author,
                    style = typo().bodySmall.copy(fontSize = 12.sp, fontWeight = if (comment.isCreator) FontWeight.Bold else FontWeight.Normal),
                    color = if (comment.isCreator) Color.White else Ytm.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ${comment.publishedTime}", style = typo().bodySmall.copy(fontSize = 12.sp), color = Ytm.TextSecondary, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            Text(comment.text, style = typo().bodyMedium.copy(fontSize = 14.sp), color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val liked = comment.likeState == CommentLikeState.LIKED
                val disliked = comment.likeState == CommentLikeState.DISLIKED
                SmallAction(enabled = comment.likeAction != null, onClick = { state.like(comment) }) {
                    Icon(SimpIcons.ThumbUp, contentDescription = null, tint = if (liked) Color.White else Ytm.TextSecondary, modifier = Modifier.size(16.dp))
                }
                val count = if (liked) comment.likeCountLiked else comment.likeCount
                if (count.isNotBlank() && count != "0") Text(count, style = typo().bodySmall, color = Ytm.TextSecondary)
                SmallAction(enabled = comment.dislikeAction != null, onClick = { state.dislike(comment) }) {
                    Icon(SimpIcons.ThumbDown, contentDescription = null, tint = if (disliked) Color.White else Ytm.TextSecondary, modifier = Modifier.size(16.dp))
                }
                if (comment.replyParams != null) {
                    Text(
                        stringResource(Res.string.ytm_comments_reply),
                        style = typo().bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = Ytm.TextSecondary,
                        modifier = Modifier.clip(CircleShape).clickable { onReply(comment) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            if (comment.repliesLabel != null && comment.repliesToken != null) {
                val open = state.threads.containsKey(comment.id)
                Text(
                    (if (open) "▲ " else "▼ ") + comment.repliesLabel,
                    style = typo().bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = LinkBlue,
                    modifier = Modifier.clip(CircleShape).clickable { state.toggleReplies(comment) }.padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SmallAction(
    enabled: Boolean,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Avatar(
    url: String?,
    sizeDp: Int,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(sizeDp.dp).clip(CircleShape).background(Ytm.Raised),
    )
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ytm.TextSecondary, style = typo().bodyMedium)
    }
}
