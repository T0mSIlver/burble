package dev.silver.burble

import dev.silver.burble.BubbleShow.ALWAYS
import dev.silver.burble.BubbleShow.TYPING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityTest {
    @Test fun eachModeShowsWhereItShould() {
        val v = Visibility<String>()
        for (dictating in listOf(false, true)) for (field in listOf(false, true)) {
            assertTrue(v.shows(dictating, ALWAYS, field))
            assertEquals("typing, dictating=$dictating field=$field", dictating || field, v.shows(dictating, TYPING, field))
        }
    }

    @Test fun aKeyboardThatHandsOffHidesItButNotADictation() {
        val v = Visibility<String>()
        for (mode in listOf(ALWAYS, TYPING)) {
            assertFalse(v.shows(dictating = false, mode, typing = true, keyboardHandsOff = true))
            assertTrue(v.shows(dictating = true, mode, typing = true, keyboardHandsOff = true))
        }
    }

    @Test fun aDictationShowsEvenSnoozed() {
        val v = Visibility<String>().apply { snooze("a") }
        assertTrue(v.shows(dictating = true, ALWAYS, typing = false))
        assertFalse(v.shows(dictating = false, ALWAYS, typing = true))
    }

    @Test fun alwaysWakesOnlyAtTheNextUnlock() {
        val v = Visibility<String>().apply { snooze("a") }
        v.focus("b", ALWAYS)
        v.focus(null, ALWAYS)
        assertTrue(v.snoozed)
        v.wake()
        assertFalse(v.snoozed)
    }

    @Test fun whileTypingWakesAtAnotherField() {
        val v = Visibility<String>().apply { snooze("a") }
        v.focus("a", TYPING)
        assertTrue("same field, still focused", v.snoozed)
        v.focus("b", TYPING)
        assertFalse(v.snoozed)
    }

    @Test fun whileTypingWakesAtTheSameFieldAfterFocusLeft() {
        val v = Visibility<String>().apply { snooze("a") }
        v.focus(null, TYPING)
        assertTrue(v.snoozed)
        v.focus("a", TYPING)
        assertFalse(v.snoozed)
    }

    @Test fun snoozedWithNoFieldWakesAtAnyField() {
        val v = Visibility<String>().apply { snooze(null) }
        v.focus("a", TYPING)
        assertFalse(v.snoozed)
    }
}
