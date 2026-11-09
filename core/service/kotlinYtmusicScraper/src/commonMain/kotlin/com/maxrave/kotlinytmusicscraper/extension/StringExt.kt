package com.maxrave.kotlinytmusicscraper.extension


fun String.verifyYouTubePlaylistId(): String = if (startsWith("VL")) this else "VL$this"

