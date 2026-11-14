package com.maxrave.logger

import co.touchlab.kermit.Logger

object Logger {
    private val logger = Logger

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
        logger.i(tag = tag, message = { message })
    }

    fun w(
        tag: String,
        message: String,
    ) {
        logger.w(tag = tag, message = { message })
    }

    fun e(
        tag: String,
        message: String,
        e: Throwable? = null,
    ) {
        logger.e(throwable = e, tag = tag, message = { message })
    }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}