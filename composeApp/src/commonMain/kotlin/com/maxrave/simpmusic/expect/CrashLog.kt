package com.maxrave.simpmusic.expect

/** The on-device crash log, for Settings › About › Share crash log; null where there is none. */
expect fun readCrashLog(): String?
