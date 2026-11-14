package com.maxrave.simpmusic.viewModel

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.viewModel.base.BaseViewModel

/**
 * The sign-in page's view model. The YouTube Music sign-in itself goes through
 * [SettingsViewModel.addAccount]; the Spotify, Discord and Last.fm sign-ins this used to carry went
 * with those features.
 */
class LogInViewModel(
    @Suppress("unused") private val dataStoreManager: DataStoreManager,
) : BaseViewModel()
