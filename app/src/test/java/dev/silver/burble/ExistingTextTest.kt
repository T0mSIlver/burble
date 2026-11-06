package dev.silver.burble

import dev.silver.burble.InsertionService.Companion.existingText
import org.junit.Assert.assertEquals
import org.junit.Test

class ExistingTextTest {
    @Test fun aHintIsNotText() {
        assertEquals("", existingText("Search settings", "Search settings", showingHint = true))
        // WhatsApp's message box reports its hint as its text without the flag.
        assertEquals("", existingText("Message", "Message", showingHint = false))
    }

    @Test fun typedTextStays() {
        assertEquals("Hi there", existingText("Hi there", "Message", showingHint = false))
        assertEquals("Hi", existingText("Hi", null, showingHint = false))
    }
}
