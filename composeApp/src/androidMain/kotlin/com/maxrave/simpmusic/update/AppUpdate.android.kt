package com.maxrave.simpmusic.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AppUpdater"
internal const val UPDATER_PREFS = "app_updater"
private const val PREFS = UPDATER_PREFS
private const val KEY_LAST_CHECK = "last_check"
internal const val KEY_NOTIFIED_VERSION = "notified_version"
private const val START_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

internal fun fetchLatestAndroidRelease(): AppRelease? {
    val connection = (URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection)
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "RefineMusic")
        if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        return parseLatestRelease(body, Build.SUPPORTED_ABIS.firstOrNull())
    } finally {
        connection.disconnect()
    }
}

private val instance: AndroidAppUpdater by lazy { AndroidAppUpdater(getKoin().get<Context>().applicationContext) }

actual fun createAppUpdater(): AppUpdater? = instance

private class AndroidAppUpdater(
    private val context: Context,
) : AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var job: Job? = null

    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val lastCheckMillis: StateFlow<Long?> =
        MutableStateFlow(prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 })

    private val updatesDir get() = File(context.cacheDir, "updates")

    private fun apkFile(release: AppRelease) = File(updatesDir, "RefineMusic-${release.version}.apk")

    override fun checkOnStart() {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val notified = prefs.getString(KEY_NOTIFIED_VERSION, null)
        val pending = notified != null && UpdateVersion.isNewer(notified, VersionManager.getVersionName())
        if (!pending && System.currentTimeMillis() - last < START_CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    override fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                if (manual) state.value = UpdateState.Checking
                val release =
                    try {
                        withContext(Dispatchers.IO) { fetchLatest() }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Update check failed: ${e.message}")
                        state.value = if (manual) UpdateState.Failed(UpdateFailure.CHECK, null) else UpdateState.Idle
                        return@launch
                    }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                (lastCheckMillis as MutableStateFlow).value = now
                val installed = VersionManager.getVersionName()
                state.value =
                    when {
                        release != null && UpdateVersion.isNewer(release.version, installed) -> UpdateState.Available(release)
                        manual -> UpdateState.UpToDate
                        else -> UpdateState.Idle
                    }
                if (state.value !is UpdateState.Available) clearDownloads()
            }
    }

    private fun fetchLatest(): AppRelease? = fetchLatestAndroidRelease()

    override fun update() {
        val release =
            when (val s = state.value) {
                is UpdateState.Available -> s.release
                is UpdateState.NeedsInstallPermission -> s.release
                is UpdateState.Failed -> s.release
                else -> null
            } ?: return
        if (job?.isActive == true) return
        UpdateCheckWorker.clear(context)
        if (!canInstall()) {
            state.value = UpdateState.NeedsInstallPermission(release)
            openInstallPermission()
            return
        }
        job =
            scope.launch {
                val apk = apkFile(release)
                if (!apk.exists() || (release.assetSize > 0 && apk.length() != release.assetSize)) {
                    state.value = UpdateState.Downloading(release, if (release.assetSize > 0) 0f else -1f)
                    val ok =
                        try {
                            withContext(Dispatchers.IO) { download(release, apk) }
                            true
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                apk.delete()
                                throw e
                            }
                            Logger.w(TAG, "Update download failed: ${e.message}")
                            apk.delete()
                            false
                        }
                    if (!ok) {
                        state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, release)
                        return@launch
                    }
                }
                if (!withContext(Dispatchers.IO) { signedLikeThisApp(apk) }) {
                    apk.delete()
                    state.value = UpdateState.Failed(UpdateFailure.NOT_SIGNED_BY_REFINE, release)
                    return@launch
                }
                state.value = UpdateState.Available(release)
                install(apk)
            }
    }

    private suspend fun download(
        release: AppRelease,
        target: File,
    ) {
        clearDownloads()
        updatesDir.mkdirs()
        val part = File(updatesDir, target.name + ".part")
        val connection = (URL(release.assetUrl).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "RefineMusic")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.assetSize
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastShown) {
                                lastShown = percent
                                state.value = UpdateState.Downloading(release, percent / 100f)
                            }
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) error("incomplete: ${part.length()} of $total")
            if (!part.renameTo(target)) error("could not move the download into place")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun signedLikeThisApp(apk: File): Boolean {
        val pm = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) {
            Logger.w(TAG, "Update is ${archive.packageName}, not ${context.packageName}")
            return false
        }
        archive.applicationInfo?.apply {
            sourceDir = apk.path
            publicSourceDir = apk.path
        }
        val theirs = signatures(archive)
        if (theirs.isEmpty()) return true
        val ours = signatures(pm.getPackageInfo(context.packageName, flags))
        return ours.isNotEmpty() && theirs == ours
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else {
            info.signatures?.toSet().orEmpty()
        }

    private fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermission() {
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Logger.w(TAG, "No install permission page: ${it.message}") }
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apk)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Logger.w(TAG, "Could not open the installer: ${it.message}")
            state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, (state.value as? UpdateState.Available)?.release)
        }
    }

    private fun clearDownloads() {
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    override fun dismiss() {
        when (state.value) {
            is UpdateState.Downloading -> job?.cancel()
            else -> Unit
        }
        state.value = UpdateState.Idle
    }
}
