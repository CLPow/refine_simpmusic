package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import com.maxrave.common.Config
import com.maxrave.common.QUALITY
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.extension.toNetScapeString
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.inject
import org.jetbrains.compose.resources.getString as formatString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.clear_listening_history
import simpmusic.composeapp.generated.resources.clear_downloaded_cache
import simpmusic.composeapp.generated.resources.clear_listening_history_done
import simpmusic.composeapp.generated.resources.clear_player_cache
import simpmusic.composeapp.generated.resources.clear_thumbnail_cache
import simpmusic.composeapp.generated.resources.error
import kotlin.coroutines.cancellation.CancellationException

class SettingsViewModel(
    private val dataStoreManager: DataStoreManager,
    private val commonRepository: CommonRepository,
    private val songRepository: SongRepository,
    private val accountRepository: AccountRepository,
    private val cacheRepository: CacheRepository,
) : BaseViewModel() {
    private val downloadUtils: DownloadHandler by inject()

    private var _location: MutableStateFlow<String?> = MutableStateFlow(null)
    val location: StateFlow<String?> = _location
    private var _loggedIn: MutableStateFlow<String?> = MutableStateFlow(null)
    val loggedIn: StateFlow<String?> = _loggedIn
    private var _sendBackToGoogle: MutableStateFlow<String?> = MutableStateFlow(null)
    val sendBackToGoogle: StateFlow<String?> = _sendBackToGoogle
    private val _explicitContentEnabled = MutableStateFlow(false)
    val explicitContentEnabled: StateFlow<Boolean> = _explicitContentEnabled

    private val _downloadQuality = MutableStateFlow<String?>(null)
    val downloadQuality: StateFlow<String?> = _downloadQuality

    private val collectors = mutableMapOf<String, Job>()

    /**
     * Runs [block] (a collector that never ends) as the only one under [key]. The getters below are
     * called again by every setter and every time a Settings page opens, and each call used to
     * start one more endless collector on top of the earlier ones.
     */
    private fun collectOnce(
        key: String,
        block: suspend () -> Unit,
    ) {
        collectors.remove(key)?.cancel()
        collectors[key] = viewModelScope.launch { block() }
    }

    fun getData() {
        getLocation()
        getQuality()
        getPlayerCacheSize()
        getDownloadedCacheSize()
        getLoggedIn()
        getSendBackToGoogle()
        getExplicitContentEnabled()
        getDownloadQuality()
    }

    private fun getDownloadQuality() {
        collectOnce("getDownloadQuality") {
            dataStoreManager.downloadQuality.collect { quality ->
                _downloadQuality.emit(QUALITY.normalize(quality))
            }
        }
    }

    fun setDownloadQuality(quality: String) {
        viewModelScope.launch {
            dataStoreManager.setDownloadQuality(quality)
            getDownloadQuality()
        }
    }

    private fun getExplicitContentEnabled() {
        collectOnce("getExplicitContentEnabled") {
            dataStoreManager.explicitContentEnabled.collect { enabled ->
                _explicitContentEnabled.value = enabled == DataStoreManager.TRUE
            }
        }
    }

    fun setExplicitContentEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStoreManager.setExplicitContentEnabled(enabled)
            getExplicitContentEnabled()
        }
    }

    fun getLocation() {
        collectOnce("getLocation") {
            dataStoreManager.location.collect { location ->
                _location.emit(location)
            }
        }
    }

    fun getLoggedIn() {
        collectOnce("getLoggedIn") {
            dataStoreManager.loggedIn.collect { loggedIn ->
                _loggedIn.emit(loggedIn)
            }
        }
    }

    fun changeLocation(location: String) {
        viewModelScope.launch {
            dataStoreManager.setLocation(location)
            getLocation()
        }
    }

    private var _quality: MutableStateFlow<String?> = MutableStateFlow(null)
    val quality: StateFlow<String?> = _quality

    fun getQuality() {
        collectOnce("getQuality") {
            dataStoreManager.quality.collect { quality ->
                _quality.emit(QUALITY.normalize(quality))
            }
        }
    }

    fun changeQuality(qualityItem: String?) {
        viewModelScope.launch {
            log("changeQuality: $qualityItem")
            dataStoreManager.setQuality(qualityItem ?: QUALITY.items.first().toString())
            getQuality()
        }
    }

    private val _cacheSize: MutableStateFlow<Long?> = MutableStateFlow(null)
    var cacheSize: StateFlow<Long?> = _cacheSize

    fun getPlayerCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = cacheRepository.getCacheSize(Config.PLAYER_CACHE)
        }
    }

    fun clearPlayerCache() {
        viewModelScope.launch {
            cacheRepository.clearCache(Config.PLAYER_CACHE)
            makeToast(getString(Res.string.clear_player_cache))
            getPlayerCacheSize()
        }
    }

    private val _downloadedCacheSize: MutableStateFlow<Long?> = MutableStateFlow(null)
    var downloadedCacheSize: StateFlow<Long?> = _downloadedCacheSize

    fun getDownloadedCacheSize() {
        viewModelScope.launch {
            _downloadedCacheSize.value = cacheRepository.getCacheSize(Config.DOWNLOAD_CACHE)
        }
    }

    fun clearDownloadedCache() {
        viewModelScope.launch {
            cacheRepository.clearCache(Config.DOWNLOAD_CACHE)
            songRepository.getDownloadedSongs().singleOrNull()?.let { songs ->
                songs.forEach { song ->
                    songRepository.updateDownloadState(song.videoId, DownloadState.STATE_NOT_DOWNLOADED)
                }
            }
            makeToast(getString(Res.string.clear_downloaded_cache))
            getDownloadedCacheSize()
            downloadUtils.removeAllDownloads()
        }
    }

    fun clearListeningHistory() {
        viewModelScope.launch {
            // The sweep walks every container table and then the whole song table, and finishes with
            // a VACUUM — seconds of work on a large library, and the screen would look frozen.
            showLoadingDialog(getString(Res.string.clear_listening_history))
            val removed =
                try {
                    // NonCancellable because the sweep is only coherent once it has finished: the
                    // cached containers are deleted first and the songs they were holding alive
                    // last, so a run stopped in between — the user leaving Settings is enough to do
                    // it — has already dropped every cached playlist, album and artist and left the
                    // songs behind. Nothing resumes it afterwards, so it has to run to the end.
                    withContext(NonCancellable) { songRepository.clearHistoryAndOrphanedSongs() }
                } catch (e: CancellationException) {
                    // Not a failure, and it must not be swallowed: catching it breaks structured
                    // concurrency, and it would put the generic error toast in front of a user whose
                    // sweep had in fact just completed, inviting them to run the whole thing again.
                    throw e
                } catch (e: Throwable) {
                    Logger.e(tag, "clearHistoryAndOrphanedSongs failed: ${e.stackTraceToString()}")
                    null
                } finally {
                    // In a finally so a failure — or the user leaving the screen — cannot strand the
                    // dialog on top of the app with no way to dismiss it.
                    hideLoadingDialog()
                }
            if (removed == null) {
                makeToast(getString(Res.string.error))
                return@launch
            }
            makeToast(formatString(Res.string.clear_listening_history_done, removed))
        }
    }

    fun getSendBackToGoogle() {
        collectOnce("getSendBackToGoogle") {
            dataStoreManager.sendBackToGoogle.collect { sendBackToGoogle ->
                _sendBackToGoogle.emit(sendBackToGoogle)
            }
        }
    }

    fun setSendBackToGoogle(sendBackToGoogle: Boolean) {
        viewModelScope.launch {
            dataStoreManager.setSendBackToGoogle(sendBackToGoogle)
            getSendBackToGoogle()
        }
    }

    private var _googleAccounts: MutableStateFlow<LocalResource<List<GoogleAccountEntity>>> =
        MutableStateFlow(LocalResource.Loading())
    val googleAccounts: StateFlow<LocalResource<List<GoogleAccountEntity>>> = _googleAccounts

    fun getAllGoogleAccount() {
        Logger.w("getAllGoogleAccount", "getAllGoogleAccount: Go to function")
        collectOnce("getAllGoogleAccount") {
            _googleAccounts.emit(LocalResource.Loading())
            accountRepository.getGoogleAccounts().collectLatest { accounts ->
                Logger.w("getAllGoogleAccount", "getAllGoogleAccount: $accounts")
                if (!accounts.isNullOrEmpty()) {
                    _googleAccounts.emit(LocalResource.Success(accounts))
                } else {
                    if (loggedIn.value == DataStoreManager.TRUE) {
                        accountRepository
                            .getAccountInfo(
                                dataStoreManager.cookie.first(),
                            ).collect {
                                Logger.w("getAllGoogleAccount", "getAllGoogleAccount: $it")
                                if (it.isNotEmpty()) {
                                    dataStoreManager.putString("AccountName", it.first().name)
                                    dataStoreManager.putString(
                                        "AccountThumbUrl",
                                        it
                                            .first()
                                            .thumbnails
                                            .lastOrNull()
                                            ?.url ?: "",
                                    )
                                    accountRepository
                                        .insertGoogleAccount(
                                            GoogleAccountEntity(
                                                email = it.first().email,
                                                name = it.first().name,
                                                thumbnailUrl =
                                                    it
                                                        .first()
                                                        .thumbnails
                                                        .lastOrNull()
                                                        ?.url ?: "",
                                                cache = accountRepository.getYouTubeCookie(),
                                                pageId = it.first().pageId,
                                                authUser = it.first().authUser,
                                                isUsed = true,
                                            ),
                                        ).singleOrNull()
                                        ?.let { account ->
                                            Logger.w("getAllGoogleAccount", "inserted: $account")
                                        }
                                    getAllGoogleAccount()
                                } else {
                                    _googleAccounts.emit(LocalResource.Success(emptyList()))
                                }
                            }
                    } else {
                        _googleAccounts.emit(LocalResource.Success(emptyList()))
                    }
                }
            }
        }
    }

    suspend fun addAccount(
        cookie: String,
        netscapeCookie: String? = null,
    ): Boolean {
        val currentCookie = dataStoreManager.cookie.first()
        val currentPageId = dataStoreManager.pageId.first()
        val currentAuthUser = dataStoreManager.authUser.first()
        val currentLoggedIn = dataStoreManager.loggedIn.first() == DataStoreManager.TRUE
        try {
            dataStoreManager.setCookie(cookie, "")
            dataStoreManager.setLoggedIn(true)
            return accountRepository
                .getAccountInfo(
                    cookie,
                ).lastOrNull()
                ?.takeIf {
                    it.isNotEmpty()
                }?.let { accountInfoList ->
                    Logger.d("getAllGoogleAccount", "addAccount: $accountInfoList")
                    accountRepository.getGoogleAccounts().lastOrNull()?.forEach {
                        Logger.d("getAllGoogleAccount", "set used: $it start")
                        accountRepository
                            .updateGoogleAccountUsed(it.email, false)
                            .singleOrNull()
                            ?.let {
                                Logger.w("getAllGoogleAccount", "set used: $it")
                            }
                    }
                    dataStoreManager.putString("AccountName", accountInfoList.first().name)
                    dataStoreManager.putString(
                        "AccountThumbUrl",
                        accountInfoList
                            .first()
                            .thumbnails
                            .lastOrNull()
                            ?.url ?: "",
                    )
                    val cookieItem =
                        netscapeCookie ?: commonRepository
                            .getCookiesFromInternalDatabase(Config.YOUTUBE_MUSIC_MAIN_URL, getPackageName())
                            .toNetScapeString()
                    commonRepository.writeTextToFile(cookieItem, (getFileDir() + "/ytdlp-cookie.txt")).let {
                        Logger.d("getAllGoogleAccount", "addAccount: write cookie file: $it")
                    }
                    accountInfoList.forEachIndexed { index, account ->
                        accountRepository
                            .insertGoogleAccount(
                                GoogleAccountEntity(
                                    email = account.email,
                                    name = account.name,
                                    thumbnailUrl =
                                        account
                                            .thumbnails
                                            .lastOrNull()
                                            ?.url ?: "",
                                    cache = cookie,
                                    isUsed = index == 0,
                                    netscapeCookie = cookieItem,
                                    pageId = account.pageId,
                                    authUser = account.authUser,
                                ),
                            ).firstOrNull()
                            ?.let {
                                log("addAccount: $it", LogLevel.WARN)
                            }
                    }
                    dataStoreManager.setLoggedIn(true)
                    dataStoreManager.setCookie(cookie, accountInfoList.first().pageId, accountInfoList.first().authUser)
                    getAllGoogleAccount()
                    getLoggedIn()
                    // Deliberately NOT importing followed artists here.
                    //
                    // Doing it on sign-in fired up to MAX_SUBSCRIPTION_PAGES paged browse calls the
                    // instant a session came up, racing the home feed's own first load on the same
                    // fresh credential. That is exactly the shape that earns a throttled session,
                    // and it lines up with a home feed that came back generic and a Mix page that
                    // crawled. Convenience is not worth spending the account's goodwill at its most
                    // fragile moment — the import stays a deliberate press in Settings.
                    true
                } ?: run {
                Logger.w("getAllGoogleAccount", "addAccount: Account info is null")
                dataStoreManager.setCookie(currentCookie, currentPageId, currentAuthUser)
                dataStoreManager.setLoggedIn(currentLoggedIn)
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Logger.e("getAllGoogleAccount", "addAccount: ${e.message}")
            // Put the previous sign-in back even when this call was cancelled (the screen closed),
            // then let the cancellation through instead of reporting it as a failed sign-in.
            withContext(NonCancellable) {
                dataStoreManager.setCookie(currentCookie, currentPageId, currentAuthUser)
                dataStoreManager.setLoggedIn(currentLoggedIn)
            }
            if (e is CancellationException) throw e
            return false
        }
    }

    fun setUsedAccount(acc: GoogleAccountEntity?) {
        viewModelScope.launch {
            if (acc != null) {
                googleAccounts.value.data?.forEach {
                    accountRepository
                        .updateGoogleAccountUsed(it.email, false)
                        .singleOrNull()
                        ?.let {
                            Logger.w("getAllGoogleAccount", "set used: $it")
                        }
                }
                dataStoreManager.putString("AccountName", acc.name)
                dataStoreManager.putString("AccountThumbUrl", acc.thumbnailUrl)
                accountRepository
                    .updateGoogleAccountUsed(acc.email, true)
                    .singleOrNull()
                    ?.let {
                        Logger.w("getAllGoogleAccount", "set used: $it")
                    }
                acc.netscapeCookie?.let { commonRepository.writeTextToFile(it, (getFileDir() + "/ytdlp-cookie.txt")) }.let {
                    Logger.d("getAllGoogleAccount", "addAccount: write cookie file: $it")
                }
                dataStoreManager.setCookie(acc.cache ?: "", acc.pageId, acc.authUser)
                dataStoreManager.setLoggedIn(true)
                delay(500)
                getAllGoogleAccount()
                getLoggedIn()
            } else {
                googleAccounts.value.data?.forEach {
                    accountRepository
                        .updateGoogleAccountUsed(it.email, false)
                        .singleOrNull()
                        ?.let {
                            Logger.w("getAllGoogleAccount", "set used: $it")
                        }
                }
                dataStoreManager.putString("AccountName", "")
                dataStoreManager.putString("AccountThumbUrl", "")
                dataStoreManager.setLoggedIn(false)
                dataStoreManager.setCookie("", null)
                delay(500)
                getAllGoogleAccount()
                getLoggedIn()
            }
        }
    }

    fun logOutAllYouTube() {
        viewModelScope.launch {
            googleAccounts.value.data?.forEach { account ->
                accountRepository.deleteGoogleAccount(account.email)
            }
            dataStoreManager.putString("AccountName", "")
            dataStoreManager.putString("AccountThumbUrl", "")
            dataStoreManager.setLoggedIn(false)
            dataStoreManager.setCookie("", null)
            delay(500)
            getAllGoogleAccount()
            getLoggedIn()
        }
    }

    @ExperimentalCoilApi
    fun clearThumbnailCache(platformContext: PlatformContext) {
        viewModelScope.launch {
            SingletonImageLoader.get(platformContext).diskCache?.clear()
            makeToast(getString(Res.string.clear_thumbnail_cache))
        }
    }
}

expect fun getPackageName(): String

expect fun getFileDir(): String
