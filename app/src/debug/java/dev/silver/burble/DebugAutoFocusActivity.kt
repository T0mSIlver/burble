package dev.silver.burble

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
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
 * Compose TextField at the bottom through a FocusRequester once composed, like the Claude app;
 * `ignores` an EditText that takes the words, typed or set through accessibility, and keeps its
 * text. The
 * keyboard stays down.
 *
 *     adb shell am start -n dev.silver.burble/.DebugAutoFocusActivity --es kind compose
 */
class DebugAutoFocusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind")
        if (kind == "edit" || kind == "ignores") {
            val field = (if (kind == "ignores") IgnoringField(this) else EditText(this))
                .apply { hint = "Focused on opening"; contentDescription = "autofocus" }
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

/** Says it took the words, typed or set through accessibility, and keeps its own text: a field they never reach. */
private class IgnoringField(context: Context) : EditText(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        action == AccessibilityNodeInfo.ACTION_SET_TEXT || super.performAccessibilityAction(action, arguments)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        super.onCreateInputConnection(outAttrs)?.let { ic ->
            object : InputConnectionWrapper(ic, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int) = true
                override fun commitText(text: CharSequence, newCursorPosition: Int, attribute: TextAttribute?) = true
            }
        }
}
