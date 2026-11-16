package com.maxrave.simpmusic.update

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val RELEASES_LATEST_URL = "https://api.github.com/repos/CLPow/refine_simpmusic/releases/latest"

data class AppRelease(
    val version: String,
    val notes: String,
    val assetUrl: String,
    val assetSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(
        val release: AppRelease,
    ) : UpdateState

    data class Downloading(
        val release: AppRelease,
        val progress: Float,
    ) : UpdateState

    data class NeedsInstallPermission(
        val release: AppRelease,
    ) : UpdateState

    data class Failed(
        val reason: UpdateFailure,
        val release: AppRelease?,
    ) : UpdateState
}

enum class UpdateFailure { CHECK, DOWNLOAD, NOT_SIGNED_BY_REFINE }

interface AppUpdater {
    val state: StateFlow<UpdateState>

    val lastCheckMillis: StateFlow<Long?>

    fun checkOnStart()

    fun checkNow()

    fun update()

    fun dismiss()
}

expect fun createAppUpdater(): AppUpdater?

object UpdateVersion {
    fun normalize(raw: String): String =
        raw
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
            .substringBefore("-")

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

fun parseLatestRelease(
    json: String,
    abi: String?,
    extension: String = ".apk",
): AppRelease? {
    val release = runCatching { releaseJson.decodeFromString<GithubRelease>(json) }.getOrNull() ?: return null
    if (release.draft || release.prerelease || release.tagName.isBlank()) return null
    val files = release.assets.filter { it.name.endsWith(extension, ignoreCase = true) && it.url.isNotBlank() }
    val file = files.firstOrNull { abi != null && it.name.contains(abi, ignoreCase = true) } ?: files.firstOrNull() ?: return null
    return AppRelease(
        version = UpdateVersion.normalize(release.tagName),
        notes = release.body.orEmpty().trim(),
        assetUrl = file.url,
        assetSize = file.size,
    )
}
