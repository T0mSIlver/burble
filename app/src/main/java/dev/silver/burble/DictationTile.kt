package dev.silver.burble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that turns the bubble on and off. The bubble lives in [InsertionService],
 * so without it the tile opens the app to set it up.
 */
class DictationTile : TileService() {

    private val scope = MainScope()
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch {
            combine(Prefs.settings, InsertionService.connected) { s, on -> tileLook(s.bubbleOn, on) }
                .collect(::render)
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (!InsertionService.connected.value) {
            openApp()
            return
        }
        Prefs.update { it.copy(bubbleOn = !it.bubbleOn) }
    }

    private fun render(look: TileLook) {
        val tile = qsTile ?: return
        tile.state = look.state
        tile.label = getString(R.string.tile_label)
        tile.subtitle = look.subtitle?.let(::getString)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** False before Android 13; the settings screen then explains how to add it by hand. */
        val canRequestAdd: Boolean get() = Build.VERSION.SDK_INT >= 33

        /**
         * Asks the system to add the tile to Quick Settings. [onResult] gets one of
         * StatusBarManager's TILE_ADD_REQUEST_RESULT_* codes, on the main thread.
         */
        fun requestAdd(context: Context, onResult: (Int) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) return
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, DictationTile::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_mic),
                context.mainExecutor,
                onResult,
            )
        }
    }
}

internal data class TileLook(val state: Int, val subtitle: Int?)

internal fun tileLook(bubbleOn: Boolean, serviceOn: Boolean): TileLook = when {
    !serviceOn -> TileLook(Tile.STATE_INACTIVE, R.string.tile_needs_service)
    bubbleOn -> TileLook(Tile.STATE_ACTIVE, null)
    else -> TileLook(Tile.STATE_INACTIVE, null)
}
