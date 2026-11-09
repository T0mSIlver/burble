# State machines

Where Burble keeps state that moves through steps, what moves it, and what checks it. A new step
goes into the type that holds the machine, not into a new flag beside it. The UX each one serves
is in the planning repo's spec/ux.md.

## Typed

| Machine | Type | States | Moved by | Checked by |
|---|---|---|---|---|
| A dictation | `Dictation.State` | Idle, Listening, Finishing, Done, Error (with `canRetry`, its audio is kept and it waits for a tap or ✕) | `Dictation.toggle`, `stop`, `retry`, `cancel`, `recover`, `acknowledge`, the session's result | `tools/emulator/*.sh` |
| What the bubble draws | `Look` (overlay) | one per dictation state, Done split into Done and Copied | `Dictation.State.look()` | `OverlayScreenshotTest`, `TransitionScreenshotTest` |
| A finger on the bubble | `Touch` (overlay) | NONE, PRESSED, DRAGGING | the touch window's events, a turn of the screen | `tools/emulator/dock-anim.sh` |
| The bubble at rest | `Rest` (overlay) | AWAKE, WAITING (2 s untouched count), RESTING | `LiveBubble`, a touch or scroll elsewhere | `RestsEarlyTest`, `dock-anim.sh` |
| ✕ snooze | `Visibility` (its `Snooze`) | none, snoozed in a field (or none), snoozed with focus gone since | `snooze`, `wake`, `focus` | `VisibilityTest` |
| The bubble's flight out of a keyboard's mic key and back | `Flight.Phase` | OUT, BACK, INTO_KEY, IN_KEY; the flight ends from any of them | `Overlay.emergeFrom`, `flyOn`, `keyboardBack`, `goHome`, `land`, `endFlight` | `tools/emulator/handoff-flight.sh` |
| The keyboard a dictation holds down | `InsertionService.keyboardHeld` | null (Android shows it), held (with whether it was up and in which window) | `hideKeyboard`, `showKeyboard` | `keyboard-back.sh`, `with-keyboard.sh` |
| Onboarding | `Step`, `Unlock` | WELCOME, INSERTION, TRY; the restricted-settings unlock REFUSED, ALLOW, TURN_ON | its buttons, and access granted while it shows | `ScreenshotTest` |
| Settings pages | `Page` stack (Home) | a stack of pages | push and Back | `PageFramesTest` (the motion) |
| Updates | `Updater.State` | Idle, Checking, UpToDate, Available, Downloading, Installing, Failed | `Updater` | by hand; `VersionTest` checks version order only |
| Test audio | `TestAudio.State` | Idle, Sending, Heard, Failed | `TestAudio.send` | by hand |

## Flags kept on purpose

These look like a machine spread over booleans, but the flags are read on different threads or
can hold together, so one enum would need a lock where there is none, or would lose states.

- `DictationSession`: its `Phase` (RECORDING, SENDING, REPORTED; a retry goes back to SENDING)
  lives under its lock, but `stopping` (polled by the recording thread, volatile) and
  `cancelled` (any point) stay beside it. `DictationSessionTest` covers segments, retry, recovery
  and the silence stop, not cancel.
- `S3Stream`: `finished` and `ended`, set from its writer and reader threads.
- `OverlayUi`'s `moving` and `docked`: they can hold at once with a touch or a rest (a docked
  bubble springing back; a spring home while a finger is down).
- `Dictation`'s `session` and `target` beside its state: an Error that waits always has its
  session, which `retry` needs; the debug hooks (`fakeListening`, `fakeError`) run without one.
