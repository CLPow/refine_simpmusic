package com.maxrave.simpmusic.expect

/** Desktop shows its crash dialog (with Copy) at the time of the crash and keeps no log file. */
actual fun readCrashLog(): String? = null
