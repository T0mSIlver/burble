package dev.silver.burble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetsTest {
    private fun pick(tapped: String?, start: String?, takes: Set<String>): String? {
        val tried = mutableListOf<String>()
        return pickTarget(tapped, start) { tried += it; it in takes }
    }

    @Test fun theLastTappedFieldFirst() = assertEquals("tapped", pick("tapped", "start", setOf("tapped", "start")))

    @Test fun theStartFieldWhenNoneWasTapped() = assertEquals("start", pick(null, "start", setOf("start")))

    @Test fun theStartFieldWhenTheTappedOneIsGone() = assertEquals("start", pick("tapped", "start", setOf("start")))

    @Test fun theClipboardWhenNeitherTakesThem() = assertNull(pick("tapped", "start", emptySet()))

    @Test fun theClipboardWithNoFieldAtAll() = assertNull(pick(null, null, emptySet()))
}
