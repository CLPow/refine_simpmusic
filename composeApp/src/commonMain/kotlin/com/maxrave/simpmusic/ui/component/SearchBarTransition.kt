package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

val SearchBarEnter: EnterTransition =
    fadeIn(tween(durationMillis = 180, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(durationMillis = 260, easing = FastOutSlowInEasing)) { -it }

val SearchBarExit: ExitTransition =
    fadeOut(tween(durationMillis = 140, easing = FastOutSlowInEasing)) +
        slideOutVertically(tween(durationMillis = 200, easing = FastOutSlowInEasing)) { -it }
