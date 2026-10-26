package dev.silver.burble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Burble's own screens in front: the bubble steps aside there, as it has nothing to type into,
 * and comes back when you leave the app. A screen that is about the bubble (a step that has you
 * try it) sets [wantsBubble] while it shows.
 */
object OwnScreens {
    private val _inFront = MutableStateFlow(false)
    val inFront: StateFlow<Boolean> = _inFront.asStateFlow()

    val wantsBubble = MutableStateFlow(false)

    internal fun shown(inFront: Boolean) {
        _inFront.value = inFront
    }
}
