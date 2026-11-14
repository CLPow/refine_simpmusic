package com.maxrave.data.platform

// Not wired up on iOS yet: treated as unmetered.
actual fun isNetworkMetered(): Boolean = false

actual fun hasValidatedInternet(): Boolean = true
