package dev.silver.burble

/**
 * Every string this client shows the server, as Gboard 18.5.3 sends it. A Gboard update that
 * changes what the server accepts should only need edits here.
 */
internal object Identity {
    const val API_KEY = "AIzaSyDbHU30I-v5OpOJm1-uff09-NJbd6I8InU"
    /** The Google app's package and signing certificate, not Gboard's: the key is the Google app's. */
    const val PACKAGE = "com.google.android.googlequicksearchbox"
    const val CERT = "58E1C4133F7441EC3D2C270270A14802DA47BA0E"

    const val URL =
        "https://speechs3proto2-pa.googleapis.com/speech.s3.S3StubbyClientConnectorService/S3Bidi"
    const val RECOGNIZER = "beyond-a2a-recognizer"
    const val CLIENT = "gboard"
    /** Gboard's versionName ("18.5.3.…") cut to major.minor, as Gboard does before sending it. */
    const val VERSION = "18.5"
    /**
     * The live value of Gboard's server-set flag jetson_experiment_recognizer_routing_key, as
     * Gboard 18.4.1 logged it (RMB-34); the app's built-in default is
     * "gboard_gemini_v3_streaming_voice_edit_mul".
     */
    const val ROUTING_KEY = "gboard-rambler"

    /** OpusTags vendor of Gboard's native Ogg writer (libintegrated_shared_object.so). */
    const val OGG_VENDOR = "Google using libopus"
    /** Gboard reads its encoder output 224 bytes at a time and sends each read as one message. */
    const val CHUNK = 224
}
