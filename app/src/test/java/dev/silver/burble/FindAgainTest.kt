package dev.silver.burble

import android.graphics.Rect
import dev.silver.burble.InsertionService.Companion.findAgain
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
}
