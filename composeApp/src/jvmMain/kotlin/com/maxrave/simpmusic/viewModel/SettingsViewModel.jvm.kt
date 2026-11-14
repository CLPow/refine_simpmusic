package com.maxrave.simpmusic.viewModel


actual fun getPackageName(): String = ""

// The app data folder (~/.simpmusic), where the player reads the cookie file. It was "", so
// signing in wrote the cookie file to the root of the drive.
actual fun getFileDir(): String = com.maxrave.data.io.fileDir()
