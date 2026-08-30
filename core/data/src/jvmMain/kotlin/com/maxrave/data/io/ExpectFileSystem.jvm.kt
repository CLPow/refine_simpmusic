package com.maxrave.data.io

import com.maxrave.common.io.appDataDirName
import okio.FileSystem
import java.io.File


actual fun fileSystem(): FileSystem {
    return FileSystem.SYSTEM
}

actual fun fileDir(): String = File(getHomeFolderPath(listOf(appDataDirName()))).absolutePath