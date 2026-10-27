package dev.silver.burble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTest {
    private val gboard = "com.google.android.inputmethod.latin"

    @Test fun onlyTheDefaultKeyboardWithAFieldFocusedMayStart() {
        assertTrue(Handoff.allows(arrayOf(gboard), gboard, fieldFocused = true))
        assertFalse("no field", Handoff.allows(arrayOf(gboard), gboard, fieldFocused = false))
        assertFalse("another app", Handoff.allows(arrayOf("com.evil"), gboard, fieldFocused = true))
        assertFalse("a keyboard that isn't the default", Handoff.allows(arrayOf(gboard), "com.swiftkey", fieldFocused = true))
        assertFalse("no default keyboard", Handoff.allows(arrayOf(gboard), null, fieldFocused = true))
        assertFalse("unknown uid", Handoff.allows(null, gboard, fieldFocused = true))
    }

    @Test fun aSharedUidCountsWhenOneOfItsPackagesIsTheKeyboard() {
        assertTrue(Handoff.allows(arrayOf("com.other", gboard), gboard, fieldFocused = true))
    }
}
