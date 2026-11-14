package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app's window is on screen: not minimized, not hidden to the tray.
 *
 * Only the desktop window writes it. Android never does, so it stays true there (the platform
 * already stops drawing a stopped Activity). The desktop player handler reads it to tick the
 * position once a second instead of ten times while nobody can see it. Audio plays either way.
 * Port of maxrave-dev/core c8999216.
 */
object AppOnScreen {
    private val _onScreen = MutableStateFlow(true)
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun set(onScreen: Boolean) {
        _onScreen.value = onScreen
    }
}
