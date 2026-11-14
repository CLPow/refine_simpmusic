package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maxrave.common.Config
import com.maxrave.simpmusic.desktop.EdgeSignIn
import com.maxrave.simpmusic.ui.component.ytm.Ytm
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_body
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_button
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_cancelled
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_failed
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_no_browser
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_title
import simpmusic.composeapp.generated.resources.ytm_desktop_sign_in_waiting

private object DesktopSignInCookies {
    @Volatile
    var cookie: String? = null
}

actual fun createWebViewCookieManager(): WebViewCookieManager =
    object : WebViewCookieManager {
        override fun getCookie(url: String): String = DesktopSignInCookies.cookie.orEmpty()

        override fun removeAllCookies() {
            DesktopSignInCookies.cookie = null
        }
    }

@Composable
actual fun PlatformWebView(
    state: MutableState<WebViewState>,
    initUrl: String,
    aboveContent: @Composable (BoxScope.() -> Unit),
    clearCookies: Boolean,
    onPageFinished: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<EdgeSignIn.Result?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.ytm_desktop_sign_in_title),
                style = typo().titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.ytm_desktop_sign_in_body),
                style = typo().bodyMedium,
                color = Ytm.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (waiting) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.ytm_desktop_sign_in_waiting), style = typo().bodyMedium, color = Color.White, textAlign = TextAlign.Center)
            } else {
                Text(
                    stringResource(Res.string.ytm_desktop_sign_in_button),
                    style = typo().bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color.Black,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                waiting = true
                                problem = null
                                scope.launch {
                                    when (val result = EdgeSignIn.signIn()) {
                                        is EdgeSignIn.Result.SignedIn -> {
                                            DesktopSignInCookies.cookie = result.cookie
                                            onPageFinished(Config.YOUTUBE_MUSIC_MAIN_URL)
                                        }
                                        else -> problem = result
                                    }
                                    waiting = false
                                }
                            }.padding(horizontal = 24.dp, vertical = 10.dp),
                )
                problem?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when (it) {
                            EdgeSignIn.Result.NoBrowser -> stringResource(Res.string.ytm_desktop_sign_in_no_browser)
                            EdgeSignIn.Result.Cancelled -> stringResource(Res.string.ytm_desktop_sign_in_cancelled)
                            else -> stringResource(Res.string.ytm_desktop_sign_in_failed)
                        },
                        style = typo().bodyMedium,
                        color = Ytm.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        aboveContent()
    }
}
