package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Only the desktop window sets this; on Android it stays true.
object AppOnScreen {
    private val _onScreen = MutableStateFlow(true)
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun set(onScreen: Boolean) {
        _onScreen.value = onScreen
    }
}
