package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toDomainAccountInfo
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.account.AccountInfo
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "AccountRepositoryImpl"

private const val VERIFY_INTERVAL_MS = 6 * 60 * 60_000L

@OptIn(ExperimentalTime::class)
internal class AccountRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : AccountRepository {
    override fun getYouTubeCookie() = youTube.cookie

    override val signInExpired: StateFlow<Boolean> = youTube.signInExpired.asStateFlow()

    private val verifyLock = Mutex()
    private var lastVerifiedAtMs = 0L
    private var lastVerifiedCookie: String? = null

    override suspend fun verifySignIn(): Boolean? =
        withContext(Dispatchers.IO) {
            verifyLock.withLock {
                val cookie = youTube.cookie
                val now = Clock.System.now().toEpochMilliseconds()
                if (cookie == lastVerifiedCookie && now - lastVerifiedAtMs < VERIFY_INTERVAL_MS) {
                    return@withContext !youTube.signInExpired.value
                }
                val result = youTube.verifySignIn()
                if (result != null) {
                    lastVerifiedCookie = cookie
                    lastVerifiedAtMs = now
                }
                result
            }
        }

    override val accountCooldownUntil: StateFlow<Long> = youTube.accountGuard.cooldownUntil

    override fun isAccountCoolingDown(): Boolean = youTube.accountGuard.isCoolingDown

    override fun resetConnections() = youTube.resetConnections()

    override fun getAccountInfo(cookie: String): Flow<List<AccountInfo>> =
        flow {
            youTube.cookie = cookie
            delay(1000)
            youTube
                .getAccountListWithPageId(cookie)
                .onSuccess {
                    emit(it.map { account -> account.toDomainAccountInfo() })
                }.onFailure {
                    Logger.e(TAG, "getAccountInfo: ${it.message}", it)
                    emit(emptyList())
                }
        }.flowOn(Dispatchers.IO)

    override fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity) =
        flow {
            emit(localDataSource.insertGoogleAccount(googleAccountEntity))
        }.flowOn(Dispatchers.IO)

    override fun getGoogleAccounts(): Flow<List<GoogleAccountEntity>?> =
        flow<List<GoogleAccountEntity>?> { emit(localDataSource.getGoogleAccounts()) }.flowOn(
            Dispatchers.IO,
        )

    override fun getUsedGoogleAccount(): Flow<GoogleAccountEntity?> =
        flow<GoogleAccountEntity?> { emit(localDataSource.getUsedGoogleAccount()) }.flowOn(
            Dispatchers.IO,
        )

    override suspend fun deleteGoogleAccount(email: String) =
        withContext(Dispatchers.IO) {
            localDataSource.deleteGoogleAccount(email)
        }

    override fun updateGoogleAccountUsed(
        email: String,
        isUsed: Boolean,
    ): Flow<Int> = flow { emit(localDataSource.updateGoogleAccountUsed(email, isUsed)) }.flowOn(Dispatchers.IO)
}