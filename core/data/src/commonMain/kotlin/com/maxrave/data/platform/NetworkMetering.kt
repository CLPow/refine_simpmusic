package com.maxrave.data.platform

/** True when the active network is metered (mobile data, a metered hotspot). */
expect fun isNetworkMetered(): Boolean

/** True when the active network has a validated internet connection (not just a link). */
expect fun hasValidatedInternet(): Boolean
