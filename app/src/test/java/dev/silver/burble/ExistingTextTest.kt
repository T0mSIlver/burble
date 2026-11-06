package dev.silver.burble

import dev.silver.burble.InsertionService.Companion.existingText
import org.junit.Assert.assertEquals
import org.junit.Test

class ExistingTextTest {
    @Test fun aHintIsNotText() {
        assertEquals("", existingText("Search settings", showingHint = true, selectable = false))
        // WhatsApp's message box: its own hint as plain text, no flag, nothing to select.
        assertEquals("", existingText("Message", showingHint = false, selectable = false))
    }

    @Test fun typedTextStays() {
        assertEquals("Hi there", existingText("Hi there", showingHint = false, selectable = true))
        // Typed text that happens to read like the field's hint is still text.
        assertEquals("Message", existingText("Message", showingHint = false, selectable = true))
    }
}
