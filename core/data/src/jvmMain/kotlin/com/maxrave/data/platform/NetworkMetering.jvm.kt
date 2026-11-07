package com.maxrave.data.platform

// A desktop is treated as unmetered: no mobile-quality or Wi-Fi-only rules.
actual fun isNetworkMetered(): Boolean = false

// No reliable check here; true keeps the desktop on its previous behaviour (mpv reports its
// own errors as timeouts, which pause).
actual fun hasValidatedInternet(): Boolean = true
