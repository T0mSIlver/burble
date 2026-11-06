package dev.silver.burble

import dev.silver.burble.InsertionService.Companion.existingText
import org.junit.Assert.assertEquals
import org.junit.Test

class ExistingTextTest {
    @Test fun aHintIsNotText() {
        assertEquals("", existingText("Search settings", "Search settings", showingHint = true, selectable = false))
        assertEquals("", existingText("Message", "Message", showingHint = false, selectable = true))
        // WhatsApp's message box: its own hint as plain text, no hint, no flag, nothing to select.
        assertEquals("", existingText("Message", null, showingHint = false, selectable = false))
    }

    @Test fun typedTextStays() {
        assertEquals("Hi there", existingText("Hi there", "Message", showingHint = false, selectable = true))
        assertEquals("Hi", existingText("Hi", null, showingHint = false, selectable = true))
    }
}
