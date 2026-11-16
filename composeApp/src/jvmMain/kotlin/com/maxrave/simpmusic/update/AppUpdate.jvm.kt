package com.maxrave.simpmusic.update

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.prefs.Preferences
import kotlin.system.exitProcess

private const val TAG = "DesktopUpdater"
private const val KEY_LAST_CHECK = "last_check"
private const val KEY_NOTIFIED_VERSION = "notified_version"
private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
private const val EXE_NAME = "RefineMusic.exe"

object DesktopUpdateNotice {
    private val _available = MutableStateFlow<AppRelease?>(null)
    val available: StateFlow<AppRelease?> = _available.asStateFlow()

    internal fun post(release: AppRelease) {
        _available.value = release
    }

    fun consumed() {
        _available.value = null
    }
}

private val instance: AppUpdater? by lazy { installDirectory()?.let { DesktopAppUpdater(it) } }

actual fun createAppUpdater(): AppUpdater? = instance

private fun installDirectory(): File? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return null
    val command = ProcessHandle.current().info().command().orElse(null) ?: return null
    val exe = File(command)
    if (!exe.name.equals(EXE_NAME, ignoreCase = true)) return null
    val dir = exe.parentFile ?: return null
    return dir.takeIf { File(it, "app").isDirectory && File(it, "runtime").isDirectory }
}

private class DesktopAppUpdater(
    private val installDir: File,
) : AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = Preferences.userRoot().node("refine_music/updater")
    private var job: Job? = null
    private var periodic: Job? = null

    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val lastCheckMillis: StateFlow<Long?> =
        MutableStateFlow(prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 })

    private val updatesDir get() = File(System.getProperty("java.io.tmpdir"), "RefineMusicUpdate")

    override fun checkOnStart() {
        if (periodic == null) {
            periodic =
                scope.launch {
                    while (true) {
                        delay(CHECK_INTERVAL_MS)
                        check(manual = false)
                    }
                }
        }
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Update check failed: ${e.message}")
                        state.value = if (manual) UpdateState.Failed(UpdateFailure.CHECK, null) else UpdateState.Idle
                        return@launch
                    }
                val now = System.currentTimeMillis()
                prefs.putLong(KEY_LAST_CHECK, now)
                (lastCheckMillis as MutableStateFlow).value = now
                val newer = release != null && UpdateVersion.isNewer(release.version, VersionManager.getVersionName())
                state.value =
                    when {
                        newer -> UpdateState.Available(release!!)
                        manual -> UpdateState.UpToDate
                        else -> UpdateState.Idle
                    }
                if (newer && prefs.get(KEY_NOTIFIED_VERSION, null) != release!!.version) {
                    prefs.put(KEY_NOTIFIED_VERSION, release.version)
                    DesktopUpdateNotice.post(release)
                }
            }
    }

    private fun fetchLatest(): AppRelease? {
        val connection = (URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RefineMusic")
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseLatestRelease(body, null, ".msi")
        } finally {
            connection.disconnect()
        }
    }

    override fun update() {
        val release =
            when (val s = state.value) {
                is UpdateState.Available -> s.release
                is UpdateState.Failed -> s.release
                else -> null
            } ?: return
        if (job?.isActive == true) return
        DesktopUpdateNotice.consumed()
        job =
            scope.launch {
                val msi = File(updatesDir, "RefineMusic-${release.version}.msi")
                state.value = UpdateState.Downloading(release, if (release.assetSize > 0) 0f else -1f)
                try {
                    withContext(Dispatchers.IO) { download(release, msi) }
                } catch (e: CancellationException) {
                    msi.delete()
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Update download failed: ${e.message}")
                    msi.delete()
                    state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, release)
                    return@launch
                }
                try {
                    launchInstaller(msi)
                } catch (e: Exception) {
                    Logger.w(TAG, "Could not start the updater: ${e.message}")
                    state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, release)
                    return@launch
                }
                Logger.w(TAG, "Closing to install ${release.version}")
                runCatching { getKoin().get<MediaPlayerHandler>().release() }
                exitProcess(0)
            }
    }

    private suspend fun download(
        release: AppRelease,
        target: File,
    ) {
        updatesDir.listFiles()?.forEach { it.deleteRecursively() }
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
                    val buffer = ByteArray(256 * 1024)
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
            if (release.assetSize > 0 && part.length() != release.assetSize) error("size differs from the release")
            if (!part.renameTo(target)) error("could not move the download into place")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    private fun launchInstaller(msi: File) {
        val script = File(updatesDir, "apply-update.ps1")
        script.writeText(APPLY_SCRIPT)
        ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            script.absolutePath,
            "-ParentPid",
            ProcessHandle.current().pid().toString(),
            "-Msi",
            msi.absolutePath,
            "-InstallDir",
            installDir.absolutePath,
        ).redirectErrorStream(true)
            .redirectOutput(File(updatesDir, "apply-update.log"))
            .start()
    }

    override fun dismiss() {
        if (state.value is UpdateState.Downloading) job?.cancel()
        state.value = UpdateState.Idle
    }
}

private val APPLY_SCRIPT =
    """
    param([int]${'$'}ParentPid, [string]${'$'}Msi, [string]${'$'}InstallDir)
    ${'$'}ErrorActionPreference = 'Continue'
    try { Wait-Process -Id ${'$'}ParentPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}
    Get-Process -Name RefineMusic -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.Path -like (Join-Path ${'$'}InstallDir '*') } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    ${'$'}work = Join-Path (Split-Path ${'$'}Msi) 'extracted'
    Remove-Item ${'$'}work -Recurse -Force -ErrorAction SilentlyContinue
    ${'$'}p = Start-Process msiexec.exe -ArgumentList ('/a "' + ${'$'}Msi + '" /qn TARGETDIR="' + ${'$'}work + '"') -Wait -PassThru
    "msiexec exit " + ${'$'}p.ExitCode
    ${'$'}exe = Get-ChildItem ${'$'}work -Recurse -Filter '$EXE_NAME' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (${'$'}exe -and (Test-Path (Join-Path ${'$'}exe.DirectoryName 'app')) -and (Test-Path (Join-Path ${'$'}InstallDir '$EXE_NAME'))) {
        robocopy ${'$'}exe.DirectoryName ${'$'}InstallDir /MIR /R:3 /W:1 /NFL /NDL /NJH /NP
        "robocopy exit " + ${'$'}LASTEXITCODE
    } else {
        "extracted app not found, nothing copied"
    }
    Remove-Item ${'$'}work -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item ${'$'}Msi -Force -ErrorAction SilentlyContinue
    Start-Process (Join-Path ${'$'}InstallDir '$EXE_NAME')
    """.trimIndent()
