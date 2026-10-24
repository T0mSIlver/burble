# Rambler Bubble

A floating dictation bubble for Android that talks directly to Gboard's Rambler
speech-to-text backend. Tap the bubble, talk, tap again: the text lands in the
focused field, or on the clipboard when no field has focus. The phone stays
usable while you dictate; nothing here hides the keyboard or aborts on scroll,
because dictation runs in this app, not in the keyboard.

## How to use

1. Install the APK, open the app.
2. Grant the three permissions it lists: draw over other apps, microphone,
   text insertion (accessibility).
3. Tap **Start the bubble**. A gray bubble appears.
4. Focus any text field, tap the bubble, speak, tap again. The words are
   inserted at the cursor. With no field focused, the text is copied and
   announced.
5. Drag the bubble to move it.

**Send test audio** streams a bundled 12-second clip through the same pipeline
and shows what the server heard; use it to check connectivity without speaking.

## How it works

- **Protocol.** gRPC bidirectional streaming to
  `speech.s3.S3StubbyClientConnectorService/S3Bidi` on
  `speechs3proto2-pa.googleapis.com`, with hand-encoded protobuf. The message
  layout was reverse-engineered from Gboard 18.5.3; the wire format and the
   three fixed auth headers were verified against the live server from a
   standalone client before this app was written.
- **Authentication.** Three static headers the client picks: the Google app's
  API key, package name and certificate hash. No account, no OAuth. The server
  rejects requests that omit them.
- **Audio.** 16 kHz mono PCM from `AudioRecord`, encoded to 24 kbps Opus by
  `MediaCodec`, muxed into Ogg (OpusHead preskip 312), sent in 224-byte chunks.
- **Text insertion.** An accessibility service finds the focused editable and
  sets its text at the cursor; otherwise the clipboard.

## Limits

- The session cap is 300 s per dictation; the app stops at 295 s.
- Quota is enforced server-side per API key with no user identity; on
  exhaustion the app says so and waits.
- The backend is undocumented and can change or reject this client at any
  time. `Send test audio` is the first thing to try after a Gboard update.

## Building

```bash
export ANDROID_HOME=<sdk>   # platform android-37, build-tools 36+
./gradlew assembleDebug
```

Gradle 9.8, AGP 9.4 with built-in Kotlin, Java 21. When
`starbridge.mavenMirror` is set in `~/.gradle/gradle.properties`, dependencies
resolve through that mirror first.
