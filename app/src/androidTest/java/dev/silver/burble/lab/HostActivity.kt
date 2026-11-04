package dev.silver.burble.lab

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import dev.silver.burble.test.R

/** One text field, as a plain app has it: rotating recreates it, its id keeps the text. */
open class HostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 240, 48, 48)
            addView(EditText(this@HostActivity).apply {
                id = R.id.lab_field
                hint = HINT
                minLines = 3
            })
        })
    }

    companion object {
        const val HINT = "Burble lab field"
    }
}

/** The same, keeping its window through a rotation (configChanges), as Burble's own screens do. */
class KeptHostActivity : HostActivity()
