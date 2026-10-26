package dev.silver.burble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.silver.burble.ui.BurbleApp
import dev.silver.burble.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { BurbleApp() } }
    }

    override fun onStart() {
        super.onStart()
        OwnScreens.shown(true)
    }

    override fun onStop() {
        OwnScreens.shown(false)
        super.onStop()
    }
}
