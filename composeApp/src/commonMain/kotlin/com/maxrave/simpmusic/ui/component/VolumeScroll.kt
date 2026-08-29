package com.maxrave.simpmusic.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** Fraction of the full range one wheel notch moves the volume. */
private const val DEFAULT_VOLUME_SCROLL_STEP = 0.05f

/**
 * Adjusts the volume with the mouse wheel whenever the pointer is anywhere over
 * the receiver, so the whole volume cluster responds and not just the slider
 * track. Scrolling up raises the level and down lowers it, matching the
 * direction every other Windows volume control uses.
 *
 * Scroll events are consumed once they move the volume, so a wheel turn over
 * the cluster never also scrolls the content sitting behind it.
 *
 * [currentVolume] is read lazily at event time rather than captured, because an
 * in-progress slider drag holds a newer value than the player state does.
 *
 * Both lambdas go through [rememberUpdatedState]: `pointerInput(Unit)` starts a
 * single long-lived coroutine that never restarts, so it would otherwise pin the
 * lambdas from the very first composition. A caller reading a plain composable
 * parameter rather than a delegated State would then compute every adjustment
 * from the volume as it was at startup, making the level jump back toward that
 * stale value on each scroll instead of moving by one step.
 */
@Composable
fun Modifier.volumeScroll(
    currentVolume: () -> Float,
    onVolumeChange: (Float) -> Unit,
    step: Float = DEFAULT_VOLUME_SCROLL_STEP,
): Modifier {
    val latestVolume by rememberUpdatedState(currentVolume)
    val latestOnVolumeChange by rememberUpdatedState(onVolumeChange)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val delta = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                if (delta == 0f) continue
                // scrollDelta.y is positive when the wheel turns away from the
                // user, which should quieten rather than raise the volume.
                latestOnVolumeChange((latestVolume() - delta * step).coerceIn(0f, 1f))
                event.changes.forEach { it.consume() }
            }
        }
    }
}
