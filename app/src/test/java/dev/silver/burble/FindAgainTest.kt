package dev.silver.burble

import android.graphics.Rect
import dev.silver.burble.InsertionService.Companion.findAgain
import dev.silver.burble.InsertionService.Companion.findRecreated
import dev.silver.burble.InsertionService.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FindAgainTest {
    private val bar = Rect(0, 1000, 1080, 1100)

    @Test fun findsTheRedrawnBarBelowWhereItWas() {
        val fields = listOf(null to Rect(0, 200, 1080, 300), null to Rect(0, 1800, 1080, 1900))
        assertEquals(1, findAgain(null, bar, fields))
    }

    @Test fun neverPicksAFieldThatOnlyHasFocusElsewhere() {
        // A search field at the top, of another width: not the start field.
        assertNull(findAgain(null, bar, listOf(null to Rect(40, 100, 1040, 200))))
    }

    @Test fun twoCandidatesAreAmbiguous() {
        val fields = listOf(null to Rect(0, 1500, 1080, 1600), null to Rect(0, 1800, 1080, 1900))
        assertNull(findAgain(null, bar, fields))
        assertNull(findAgain("app:id/text", bar, listOf("app:id/text" to bar, "app:id/text" to Rect(bar))))
    }

    @Test fun theSameIdWins() {
        val fields = listOf(null to Rect(0, 1800, 1080, 1900), "app:id/text" to Rect(0, 50, 500, 100))
        assertEquals(1, findAgain("app:id/text", bar, fields))
    }

    // An app recreated when the screen turns: its fields are new nodes in a new window.
    private val edit = "android.widget.EditText"

    @Test fun aRecreatedFieldIsFoundByItsId() {
        val fields = listOf(Seen("app:id/to", edit, null, true), Seen("app:id/text", edit, "Message", false))
        assertEquals(1, findRecreated(Seen("app:id/text", edit, "Message", true), fields))
    }

    @Test fun anIdThatsGoneIsAnotherScreen() {
        assertNull(findRecreated(Seen("app:id/text", edit, null, true), listOf(Seen("app:id/search", edit, null, true))))
        assertNull(findRecreated(Seen("app:id/text", edit, null, true), listOf(Seen(null, edit, null, true))))
    }

    @Test fun withoutAnIdTheFieldOfItsClassWithItsHint() {
        val fields = listOf(Seen(null, edit, "Search", false), Seen(null, edit, "Message", false))
        assertEquals(1, findRecreated(Seen(null, edit, "Message", true), fields))
        assertNull(findRecreated(Seen(null, edit, "Subject", true), fields))
    }

    @Test fun twoAlikeTheFocusedOneElseNone() {
        val alike = listOf(Seen(null, edit, null, false), Seen(null, edit, null, true))
        assertEquals(1, findRecreated(Seen(null, edit, null, true), alike))
        assertNull(findRecreated(Seen(null, edit, null, true), alike.map { it.copy(focused = false) }))
    }
}
