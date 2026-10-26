package dev.silver.burble

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTest {
    @Test fun appNamesGoUnlessKept() {
        val log = "D/Insert: insert fell back: android.widget.EditText in com.whatsapp refused the text\n" +
            "W/S3Stream: java.io.IOException at dev.silver.burble.S3Stream(S3Stream.kt:90) to speech.googleapis.com"
        assertEquals(
            "D/Insert: insert fell back: android.widget.EditText in <app> refused the text\n" +
                "W/S3Stream: java.io.IOException at dev.silver.burble.S3Stream(S3Stream.kt:90) to speech.googleapis.com",
            Report.redact(log),
        )
    }
}
