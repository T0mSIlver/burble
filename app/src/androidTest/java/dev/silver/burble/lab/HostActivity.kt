package dev.silver.burble.lab

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import java.lang.ref.WeakReference
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
                field = WeakReference(this)
            })
        })
    }

    companion object {
        const val HINT = "Burble lab field"

        /**
         * The field on screen, read directly: a floating keyboard over it, as OxygenOS shows Gboard
         * in landscape, hides it from UI Automator (OnePlus 11, 2026-11-04).
         */
        @Volatile var field: WeakReference<EditText>? = null
    }
}

/** The same, keeping its window through a rotation (configChanges), as Burble's own screens do. */
class KeptHostActivity : HostActivity()
