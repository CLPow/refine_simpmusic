package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.account.AccountInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AccountRepository {
    fun getAccountInfo(cookie: String): Flow<List<AccountInfo>>

    fun getYouTubeCookie(): String?

    /** True while a cookie is saved but YouTube no longer treats it as signed in. */
    val signInExpired: StateFlow<Boolean>

    /** Checks the saved sign-in with YouTube; null when it could not be checked (offline). */
    suspend fun verifySignIn(): Boolean?

    fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity): Flow<Long>

    fun getGoogleAccounts(): Flow<List<GoogleAccountEntity>?>

    fun getUsedGoogleAccount(): Flow<GoogleAccountEntity?>

    suspend fun deleteGoogleAccount(email: String)

    fun updateGoogleAccountUsed(
        email: String,
        isUsed: Boolean,
    ): Flow<Int>
}