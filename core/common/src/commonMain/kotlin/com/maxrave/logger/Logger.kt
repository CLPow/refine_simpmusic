package com.maxrave.logger

import co.touchlab.kermit.Logger

object Logger {
    private val logger = Logger

    // Tags suppressed at all log levels. Add a tag here to silence its logs globally.
    private val mutedTags =
        setOf(
            "DiscordWebSocket",
        )

    private fun isMuted(tag: String): Boolean = tag in mutedTags

    /** Release builds keep warnings and errors only; debug/info lines sit on hot paths. */
    fun setMinLevel(level: LogLevel) {
        logger.setMinSeverity(
            when (level) {
                LogLevel.DEBUG -> co.touchlab.kermit.Severity.Debug
                LogLevel.INFO -> co.touchlab.kermit.Severity.Info
                LogLevel.WARN -> co.touchlab.kermit.Severity.Warn
                LogLevel.ERROR -> co.touchlab.kermit.Severity.Error
            },
        )
    }

    fun d(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.d(
            tag = tag,
            message = {
                message
            },
        )
    }

    fun i(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.i(tag = tag, message = { message })
    }

    fun w(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.w(tag = tag, message = { message })
    }

    fun e(
        tag: String,
        message: String,
        e: Throwable? = null,
    ) {
        if (isMuted(tag)) return
        logger.e(throwable = e, tag = tag, message = { message })
    }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}