package dev.silver.burble.ui

import androidx.compose.foundation.lazy.LazyListScope

/** Release builds have no debug rows; src/debug's version adds the temporary wave shape row. */
@Suppress("UnusedReceiverParameter")
fun LazyListScope.debugRows() = Unit
