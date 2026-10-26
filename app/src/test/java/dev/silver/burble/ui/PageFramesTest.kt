package dev.silver.burble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.silver.burble.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A page opened and closed, every 16 ms on a paused clock, as a 60 Hz screen shows it. Frames go
// to build/pages/, for review.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w200dp-h300dp-mdpi")
class PageFramesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frames() {
        val stack = mutableStateOf(listOf("home"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = false, dynamic = false) {
                PageStack(stack.value, onBack = { stack.value = stack.value.dropLast(1) }, Modifier.fillMaxSize()) { p ->
                    Box(Modifier.fillMaxSize().background(if (p == "home") Color(0xFFE8EAF6) else Color(0xFFFFF3E0)), contentAlignment = Alignment.Center) {
                        Text(p)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        for ((name, next) in listOf("open" to listOf("home", "page"), "close" to listOf("home"))) {
            stack.value = next
            for (f in 0..40) {
                compose.onRoot().captureRoboImage("build/pages/$name-%03d.png".format(f))
                compose.mainClock.advanceTimeBy(16)
            }
        }
    }
}
