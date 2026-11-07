package dev.silver.burble

import dev.silver.burble.InsertionService.Companion.existingText
import dev.silver.burble.InsertionService.Companion.landed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun wordsLandOnlyWhenTheFieldGainsThem() {
        assertTrue(landed(before = "Hi", now = "Hi there you", words = "there you"))
        // The field took the action and kept its text.
        assertFalse(landed(before = "", now = "", words = "hello"))
        // The same words were already there: one more is needed.
        assertFalse(landed(before = "yes", now = "yes", words = "yes"))
        assertTrue(landed(before = "yes", now = "yes yes", words = "yes"))
        // A field may fold the whitespace it was given.
        assertTrue(landed(before = "", now = "one two", words = "one\n two"))
    }
}
