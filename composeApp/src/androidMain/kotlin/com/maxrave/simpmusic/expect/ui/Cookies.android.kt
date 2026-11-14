package com.maxrave.simpmusic.expect.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.retry
import simpmusic.composeapp.generated.resources.unavailable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

actual fun createWebViewCookieManager(): WebViewCookieManager =
    object : WebViewCookieManager {
        override fun getCookie(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

        override fun removeAllCookies() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
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
    val cookiesCleared = remember { AtomicBoolean(false) }
    Box {
        ManagedWebView { onRendererGone ->
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        url?.let {
                            onPageFinished(it)
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean = handleRendererGone(detail, onRendererGone)
                }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            if (clearCookies && cookiesCleared.compareAndSet(false, true)) {
                createWebViewCookieManager().removeAllCookies()
            }
            loadUrl(initUrl)
        }
        aboveContent()
    }
}

@Composable
private fun ManagedWebView(configure: WebView.(onRendererGone: () -> Unit) -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val unavailable = stringResource(Res.string.unavailable)
    var ready by remember { mutableStateOf(false) }
    var startupFailed by remember { mutableStateOf(false) }
    var rendererGone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val executor =
            Executor { task ->
                Dispatchers.IO.asExecutor().execute {
                    try {
                        task.run()
                    } catch (e: Throwable) {
                        Logger.e("PlatformWebView", "WebView startup threw", e)
                        startupFailed = true
                    }
                }
            }
        WebViewCompat.startUpWebView(
            appContext,
            WebViewStartUpConfig.Builder(executor).build(),
            object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) {
                    ready = true
                }

                override fun onError(error: WebViewStartupException) {
                    Logger.e("PlatformWebView", "WebView startup failed", error)
                    startupFailed = true
                }
            },
        )
    }

    when {
        startupFailed -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(unavailable)
            }
        }

        !ready -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        rendererGone -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { rendererGone = false }) {
                    Text(stringResource(Res.string.retry), color = LocalContentColor.current)
                }
            }
        }

        else -> {
            AndroidView(
                factory = { ctx ->
                    webViewOrFallback(ctx, unavailable) { configure { rendererGone = true } }
                },
                onRelease = { view ->
                    (view as? WebView)?.run {
                        stopLoading()
                        destroy()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun handleRendererGone(
    detail: RenderProcessGoneDetail,
    onRendererGone: () -> Unit,
): Boolean {
    Logger.w("PlatformWebView", "WebView renderer gone, crashed=${detail.didCrash()}")
    onRendererGone()
    return true
}

private fun webViewOrFallback(
    context: Context,
    unavailable: String,
    setup: WebView.() -> Unit,
): View {
    val webView = runCatching { WebView(context) }.getOrElse { e -> return unavailableView(context, unavailable, e) }
    return runCatching {
        webView.apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setup()
        }
    }.getOrElse { e ->
        runCatching { webView.destroy() }
        unavailableView(context, unavailable, e)
    }
}

private fun unavailableView(
    context: Context,
    unavailable: String,
    e: Throwable,
): View {
    Logger.e("PlatformWebView", "WebView unavailable", e)
    return TextView(context).apply {
        text = unavailable
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
    }
}

