package dev.silver.burble

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * Debug builds only: a field that has focus on opening, never tapped, as chat apps do with their
 * message box. `--es kind edit` focuses an EditText in onCreate; `compose` (the default) a
 * Compose TextField at the bottom through a FocusRequester once composed, like the Claude app.
 * The keyboard stays down.
 *
 *     adb shell am start -n dev.silver.burble/.DebugAutoFocusActivity --es kind compose
 */
class DebugAutoFocusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("kind") == "edit") {
            val field = EditText(this).apply { hint = "Focused on opening"; contentDescription = "autofocus" }
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 200, 48, 48)
                addView(field)
            })
            field.requestFocus()
            return
        }
        setContent {
            val focus = remember { FocusRequester() }
            var text by remember { mutableStateOf("") }
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp)) {
                Text("A chat", Modifier.align(Alignment.TopStart))
                TextField(text, { text = it }, Modifier.fillMaxWidth().align(Alignment.BottomCenter).focusRequester(focus),
                    placeholder = { Text("Message") })
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}
