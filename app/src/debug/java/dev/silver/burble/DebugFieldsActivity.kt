package dev.silver.burble

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Debug builds only: text fields in a long scrolling page, to check on the emulator that text
 * lands in the field a dictation started in after scrolling, focus moves and the keyboard
 * hiding. Two Compose fields act as some chat apps do when the keyboard goes: one drops focus,
 * one is rebuilt, so the node captured at the start goes stale.
 *
 *     adb shell am start -n dev.silver.burble/.DebugFieldsActivity
 */
class DebugFieldsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            // Tapping the page takes focus away from the fields, as scrolling apps do.
            isFocusableInTouchMode = true
        }
        column.addView(EditText(this).apply { hint = "First field"; contentDescription = "first" })
        column.addView(ComposeView(this).apply { setContent { ComposeFields() } })
        repeat(40) { column.addView(TextView(this).apply { text = "Line $it"; textSize = 20f }) }
        column.addView(EditText(this).apply { hint = "Second field"; contentDescription = "second" })
        setContentView(ScrollView(this).apply { addView(column) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeFields() {
    val ime = WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    var plain by remember { mutableStateOf("") }
    var dropping by remember { mutableStateOf("") }
    var rebuilt by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf(0) }
    Column {
        TextField(plain, { plain = it }, placeholder = { Text("Compose field") })
        LaunchedEffect(ime) { if (!ime) { focus.clearFocus(); generation++ } }
        TextField(dropping, { dropping = it }, placeholder = { Text("Drops focus without keyboard") })
        TextField(rememberTextFieldState(), placeholder = { Text("State field, drops focus") })
        key(generation) { TextField(rebuilt, { rebuilt = it }, placeholder = { Text("Rebuilt without keyboard") }) }
    }
}
