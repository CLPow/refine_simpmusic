package com.maxrave.simpmusic.update

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where releases are published. Public repo, so the API needs no token. */
const val RELEASES_LATEST_URL = "https://api.github.com/repos/CLPow/refine_simpmusic/releases/latest"

data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Only after a check the listener asked for; the start-up check stays silent. */
    data object UpToDate : UpdateState

    data class Available(
        val release: AppRelease,
    ) : UpdateState

    /** [progress] is 0..1, or -1 while the size is unknown. */
    data class Downloading(
        val release: AppRelease,
        val progress: Float,
    ) : UpdateState

    /** Android needs "Install unknown apps" allowed for Refine before it can update itself. */
    data class NeedsInstallPermission(
        val release: AppRelease,
    ) : UpdateState

    data class Failed(
        val reason: UpdateFailure,
        val release: AppRelease?,
    ) : UpdateState
}

enum class UpdateFailure { CHECK, DOWNLOAD, NOT_SIGNED_BY_REFINE }

/**
 * In-app updates from GitHub Releases. Android only: the desktop app is installed by hand, so
 * [createAppUpdater] returns null there and every update UI is hidden.
 */
interface AppUpdater {
    val state: StateFlow<UpdateState>

    /** Epoch millis of the last successful check, or null. */
    val lastCheckMillis: StateFlow<Long?>

    /** At most once every 12 hours; says nothing unless there is an update. */
    fun checkOnStart()

    /** Settings › About › Check for updates. */
    fun checkNow()

    /** Download, verify and open the system installer (or the permission page first). */
    fun update()

    fun dismiss()
}

expect fun createAppUpdater(): AppUpdater?

object UpdateVersion {
    /** `v2.4.1` / `2.4.1` / `2.4.1-dev` → `2.4.1`. */
    fun normalize(raw: String): String =
        raw
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
            .substringBefore("-")

    /** True when [remoteTag] is a newer version than [installed], compared number by number. */
    fun isNewer(
        remoteTag: String,
        installed: String,
    ): Boolean {
        val remote = normalize(remoteTag).split('.').map { it.toIntOrNull() ?: return false }
        val local = normalize(installed).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
)

private val releaseJson = Json { ignoreUnknownKeys = true }

/**
 * The release in a `releases/latest` answer, or null when it is not one to offer (draft,
 * prerelease, no APK). With several APKs the one naming [abi] wins (e.g. `arm64-v8a`).
 */
fun parseLatestRelease(
    json: String,
    abi: String?,
): AppRelease? {
    val release = runCatching { releaseJson.decodeFromString<GithubRelease>(json) }.getOrNull() ?: return null
    if (release.draft || release.prerelease || release.tagName.isBlank()) return null
    val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.url.isNotBlank() }
    val apk = apks.firstOrNull { abi != null && it.name.contains(abi, ignoreCase = true) } ?: apks.firstOrNull() ?: return null
    return AppRelease(
        version = UpdateVersion.normalize(release.tagName),
        notes = release.body.orEmpty().trim(),
        apkUrl = apk.url,
        apkSize = apk.size,
    )
}
