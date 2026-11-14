package com.maxrave.ktorext.curl

import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException

class CurlLoggerConfig {
    var logger: (String) -> Unit = { println(it) }

    var redactHeaders: Set<String> = emptySet()

    var handleCompression: Boolean = true
}

val CurlLogger = createClientPlugin("CurlLogger", ::CurlLoggerConfig) {
    val log = pluginConfig.logger
    val redactHeaders = pluginConfig.redactHeaders.mapTo(mutableSetOf()) { it.lowercase() }
    val handleCompression = pluginConfig.handleCompression

    on(SendingRequest) { request, content ->
        try {
            log(buildCurlCommand(request, content, redactHeaders, handleCompression))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Logging must never break the actual request.
        }
    }
}

private suspend fun buildCurlCommand(
    request: HttpRequestBuilder,
    content: OutgoingContent,
    redactHeaders: Set<String>,
    handleCompression: Boolean,
): String {
    val contentLengthName = HttpHeaders.ContentLength.lowercase()
    val acceptEncodingName = HttpHeaders.AcceptEncoding.lowercase()

    val parts = mutableListOf<String>()
    parts += "curl -X ${request.method.value}"
    parts += request.url.buildString().shellQuote()

    val seenHeaders = mutableSetOf<String>()
    request.headers.entries().forEach { (name, values) ->
        val lower = name.lowercase()
        if (lower == contentLengthName) return@forEach
        if (handleCompression && lower == acceptEncodingName) return@forEach
        seenHeaders += lower
        values.forEach { value ->
            val shown = if (lower in redactHeaders) "<redacted>" else value
            parts += "-H " + "$name: $shown".shellQuote()
        }
    }
    if ("content-type" !in seenHeaders) {
        content.contentType?.let { parts += "-H " + "${HttpHeaders.ContentType}: $it".shellQuote() }
    }

    if (handleCompression) parts += "--compressed"

    content.readBodyOrNull()?.takeIf { it.isNotEmpty() }?.let {
        parts += "--data-raw " + it.shellQuote()
    }

    return parts.joinToString(" ")
}

private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

private suspend fun OutgoingContent.readBodyOrNull(): String? =
    when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> readWriteChannelBody()
        else -> null
    }

private suspend fun OutgoingContent.WriteChannelContent.readWriteChannelBody(): String? =
    try {
        coroutineScope {
            val channel = ByteChannel()
            launch {
                try {
                    writeTo(channel)
                } finally {
                    channel.flushAndClose()
                }
            }
            channel.readRemaining().readByteArray().decodeToString()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
