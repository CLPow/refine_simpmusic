package com.maxrave.simpmusic.utils

import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.explicit_content_blocked
import simpmusic.composeapp.generated.resources.this_app_needs_to_access_your_notification
import simpmusic.composeapp.generated.resources.time_out_check_internet_connection_or_change_piped_instance_in_settings
import simpmusic.composeapp.generated.resources.ytm_stream_wifi_only_blocked
import simpmusic.composeapp.generated.resources.ytm_track_skipped

object ComposeResUtils {
    suspend fun getResString(
        type: StringType,
        vararg format: String,
    ): String =
        when (type) {
            StringType.EXPLICIT_CONTENT_BLOCKED -> {
                getString(Res.string.explicit_content_blocked)
            }

            StringType.NOTIFICATION_REQUEST -> {
                getString(Res.string.this_app_needs_to_access_your_notification)
            }

            StringType.TIME_OUT_ERROR -> {
                getString(Res.string.time_out_check_internet_connection_or_change_piped_instance_in_settings, *format)
            }

            StringType.TRACK_SKIPPED -> {
                getString(Res.string.ytm_track_skipped)
            }

            StringType.WIFI_ONLY -> {
                getString(Res.string.ytm_stream_wifi_only_blocked)
            }
        }

    enum class StringType {
        EXPLICIT_CONTENT_BLOCKED,
        NOTIFICATION_REQUEST,
        TIME_OUT_ERROR,
        TRACK_SKIPPED,
        WIFI_ONLY,
    }
}
