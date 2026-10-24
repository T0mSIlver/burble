package dev.silver.burble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.silver.burble.ui.BurbleApp
import dev.silver.burble.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { BurbleApp() } }
    }

    // Opening the app is a way back to a bubble closed with ✕; the Quick Settings tile is
    // optional, so without this a user could be left with no bubble and no way to get it back.
    override fun onResume() {
        super.onResume()
        if (!Prefs.settings.value.bubbleOn) Prefs.update { it.copy(bubbleOn = true) }
    }
}
