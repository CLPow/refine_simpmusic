package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy

/**
 * Audio is all that is required: the app plays songs only. This also asked for a video itag, so a
 * signed-in extraction that returned good audio but no matching video was thrown away for the
 * anonymous fallback.
 */
internal fun List<Pair<Int, String>>.hasRequiredItags(): Boolean {
    val itags = this.mapTo(HashSet()) { it.first }
    return ITAG.AUDIO.any { it in itags }
}

private val streamHealthCheckClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .build()
}

/**
 * Pick one random URL whose itag belongs to [ITAG.AUDIO] and HEAD it. Returns true only when the
 * response code is in the 200..299 range. Only audio URLs are checked because they are the ones
 * the player will actually use.
 */
internal fun List<Pair<Int, String>>.headCheckRandomStream(): Boolean {
    val required = ITAG.AUDIO
    val candidate = this.filter { it.first in required }.randomOrNull() ?: return false
    return runCatching {
        val request =
            okhttp3.Request
                .Builder()
                .head()
                .url(candidate.second)
                .build()
        streamHealthCheckClient.newCall(request).execute().use { response ->
            response.code in 200..299
        }
    }.getOrDefault(false)
}

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val response = client.newCall(buildOkHttpRequest(request)).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val body = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val builder =
            okhttp3.Request
                .Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        request.headers().forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                builder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    builder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                builder.header(headerName, headerValueList[0])
            }
        }
        return builder.build()
    }
}
