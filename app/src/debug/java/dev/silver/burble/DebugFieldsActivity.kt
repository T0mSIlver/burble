package dev.silver.burble

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug builds only: two text fields in a long scrolling page, to check on the emulator that
 * text lands in the field a dictation started in after scrolling and focus moves.
 *
 *     adb shell am start -n dev.silver.burble/.DebugFieldsActivity
 */
class DebugFieldsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            // Tapping the page takes focus away from the fields, as scrolling apps do.
            isFocusableInTouchMode = true
        }
        column.addView(EditText(this).apply { hint = "First field"; contentDescription = "first" })
        repeat(40) { column.addView(TextView(this).apply { text = "Line $it"; textSize = 20f }) }
        column.addView(EditText(this).apply { hint = "Second field"; contentDescription = "second" })
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
