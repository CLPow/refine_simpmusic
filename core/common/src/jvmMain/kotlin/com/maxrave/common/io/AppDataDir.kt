package com.maxrave.common.io

import java.io.File

/** Directory under the user's home holding the database, settings and downloads. */
const val APP_DATA_DIR_NAME = ".refinemusic"

/** What that directory was called while this fork still shipped under the upstream name. */
private const val LEGACY_APP_DATA_DIR_NAME = ".simpmusic"

/**
 * Name of the data directory, renaming the pre-fork one into place if it is still there.
 *
 * Every desktop path — the Room database, the settings store, downloads — hangs off this, so an
 * existing install would otherwise come up empty after the rebrand, with its library stranded in a
 * directory nothing reads any more.
 *
 * Resolved once, lazily, rather than on a migration step at startup: the subsystems that open these
 * files are built by Koin in no guaranteed order, and a rename that lost the race would run with
 * the database already open. Going through here means the directory is settled before the first
 * path is handed out, whichever subsystem asks first.
 *
 * If the rename fails — a file inside is held open, or the home directory is read-only — the old
 * directory is used as-is. A working app on the old path beats a fresh, empty one on the new path.
 */
fun appDataDirName(): String = resolvedName

// `by lazy` is synchronized: two threads must not both decide the legacy directory needs renaming,
// since the one that loses the race sees renameTo fail and would fall back to a path the winner has
// already moved away.
private val resolvedName: String by lazy {
    val home = System.getProperty("user.home") ?: return@lazy APP_DATA_DIR_NAME
    val current = File(home, APP_DATA_DIR_NAME)
    if (current.isDirectory) return@lazy APP_DATA_DIR_NAME

    val legacy = File(home, LEGACY_APP_DATA_DIR_NAME)
    if (!legacy.isDirectory) return@lazy APP_DATA_DIR_NAME

    val renamed = runCatching { legacy.renameTo(current) }.getOrDefault(false)
    if (renamed) APP_DATA_DIR_NAME else LEGACY_APP_DATA_DIR_NAME
}
