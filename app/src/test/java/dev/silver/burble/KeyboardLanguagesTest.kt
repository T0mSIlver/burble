package dev.silver.burble

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLanguagesTest {
    @Test fun currentLeadsOnceEach() = assertEquals(
        listOf("fr-FR", "en-US", "de-DE"),
        KeyboardLanguages.order("fr-FR", listOf("en-US", "fr-FR", "de-DE"), listOf("en-GB")),
    )

    @Test fun noKeyboardLanguagesGiveThePhones() = assertEquals(
        listOf("en-GB", "fr-FR"),
        KeyboardLanguages.order(null, emptyList(), listOf("en-GB", "fr-FR")),
    )
}
