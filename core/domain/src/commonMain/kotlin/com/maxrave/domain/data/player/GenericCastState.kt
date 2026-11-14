package com.maxrave.domain.data.player

data class GenericCastState(
    val isRemote: Boolean = false,
    val deviceName: String? = null,
) {
    companion object {
        val NOT_CASTING = GenericCastState()
    }
}
