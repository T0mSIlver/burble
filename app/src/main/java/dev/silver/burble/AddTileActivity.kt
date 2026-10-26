package dev.silver.burble

import android.app.Activity
import android.os.Bundle

/**
 * See-through and gone once answered: Android adds a Quick Settings tile only at the request of
 * the app in front, and the bubble's hint, from the accessibility service, isn't.
 */
class AddTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) DictationTile.requestAdd(this) { finish() }
    }
}
