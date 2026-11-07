package dev.silver.burble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetsTest {
    private fun pick(tapped: String?, start: String?, takes: Set<String>): String? {
        val tried = mutableListOf<String>()
        var picked: String? = "not answered"
        pickTarget(tapped, start, { t, took -> tried += t; took(t in takes) }) { picked = it }
        return picked
    }

    @Test fun theLastTappedFieldFirst() = assertEquals("tapped", pick("tapped", "start", setOf("tapped", "start")))

    @Test fun theStartFieldWhenNoneWasTapped() = assertEquals("start", pick(null, "start", setOf("start")))

    @Test fun theStartFieldWhenTheTappedOneIsGone() = assertEquals("start", pick("tapped", "start", setOf("start")))

    @Test fun theClipboardWhenNeitherTakesThem() = assertNull(pick("tapped", "start", emptySet()))

    @Test fun theClipboardWithNoFieldAtAll() = assertNull(pick(null, null, emptySet()))

    // The start field needs no tap: a field an app focused on opening counts, found in the
    // active window, in any window, inside a focused container, or from the app's own focus
    // event, in that order; never a container or a password field.
    private data class Node(val name: String, val editable: Boolean = true, val password: Boolean = false)
    private val isField = { n: Node -> n.editable && !n.password }

    @Test fun anAppFocusedFieldIsTheStartField() {
        val box = Node("message box")
        assertEquals(box, startField(sequenceOf(box, null, null, null), isField))
    }

    @Test fun focusOnAContainerFallsThroughToTheFieldInside() {
        val container = Node("composer", editable = false)
        val box = Node("message box")
        assertEquals(box, startField(sequenceOf(container, container, box, null), isField))
    }

    @Test fun noFocusQueryFindsItButTheAppsFocusEventDid() {
        val box = Node("message box")
        assertEquals(box, startField(sequenceOf(null, null, null, box), isField))
    }

    @Test fun neverAPasswordField() {
        assertEquals(null, startField(sequenceOf(Node("pin", password = true), null, null, null), isField))
    }
}
