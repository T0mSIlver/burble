package dev.silver.burble.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    // Compared as numbers: as strings, 0.10.0 would sort before 0.9.0 and the update be missed.
    @Test fun numericParts() = assertTrue(Updater.newer("0.10.0", "0.9.0"))

    @Test fun sameIsNotNewer() = assertFalse(Updater.newer("0.2.0", "0.2.0"))

    @Test fun suffixesIgnored() {
        assertFalse(Updater.newer("0.2.0-rc.1", "0.2.0"))
        assertTrue(Updater.newer("0.2.1", "0.2.0+main.abc"))
    }
}
