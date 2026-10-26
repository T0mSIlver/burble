# Edge cases

Every shipped feature's cases, how each is covered, and its status. **Unit**: a JVM test in
`app/src/test`. **Screenshot**: a Roborazzi test writing `app/screenshots`. **Emulator**: a script
in `tools/emulator` or a scripted run on the `sb36` emulator with the debug build's hooks
(`DebugReceiver`). **Pixel**: by hand on a phone; the list at the end.

Status: ✅ covered and passing, 👤 needs the phone.

## Bubble states

| Case | Coverage | Status |
|---|---|---|
| Tap → Listening → tap or silence → Finishing → Inserted (check 0.7 s) | Unit `DictationSessionTest.stopsAfterSilence`; emulator recording `motion-r1` | ✅ |
| The text lands 0.5 s after the stop (Gboard on a Pixel: 0.7 s): each 64 ms of audio goes out as its own Ogg page, one connection serves every dictation, and the text goes in on the final result, not when the stream closes | Unit `EncoderBackpressureTest.eachBufferGoesOutAsAPage`; emulator `tools/emulator/latency.sh` (535 to 551 ms from the stop to the insert) | ✅ |
| The network changes between two dictations: the second opens a new connection instead of waiting on the dead one | Emulator: airplane mode on and off, then `latency.sh` | ✅ |
| No field, or not on screen → clipboard, Copied (check 1 s, TalkBack says it), nothing inserted later | Unit `TargetsTest`; emulator `tools/emulator/where-text-goes.sh switch` | ✅ |
| Keyboard hidden only in Listening and Finishing; back as the words land | Code (`followDictation`, `showKeyboard`); emulator `tools/emulator/keyboard-back.sh` | ✅ |
| Keyboard back in an EditText, a Compose field, Chrome's address bar, a Chrome page's textarea and Messages: started in the field, a field tapped mid-dictation (its app takes the keyboard for shown: focus taken for a frame), clipboard with a field focused; none without one | Emulator `tools/emulator/keyboard-back.sh` (0.4 to 0.7 s, the keyboard's own animation included) | ✅ |
| Error: its look 1.5 s, label 4 s; with audio kept, a retry badge until tap, ✕ or the next dictation | Code (`LiveBubble`); screenshot `OverlayScreenshotTest.looks` | ✅ |
| Failed with audio kept → retry sends the same audio, only the failed segment | Unit `DictationSessionTest.retrySendsTheSameBytes`, `retryResendsOnlyTheFailedSegment` | ✅ |
| Nothing heard, no microphone → label, back to Idle after 4 s | Screenshot `OverlayScreenshotTest.looks`; emulator recording | ✅ |
| Process stopped mid-dictation → "Dictation not sent" comes back, sends again | Unit `DictationSessionTest.aStoppedProcessLeavesAudioThatSendsAgain`, `nothingToRecoverFromAnEmptyFolder` | ✅ |
| Dictation past 5 minutes chains server sessions, no audio lost | Unit `DictationSessionTest.longDictationChainsWithoutLosingAudio`, `switchesAtTheFirstQuietBufferInTheWindow` | ✅ |
| Encoder busy or hung: no audio lost, no hang | Unit `EncoderBackpressureTest` | ✅ |
| Tap during Finishing or on the check does nothing | Code (`tapBubble`); emulator | ✅ |
| ✕ during Listening or Finishing cancels; Error drops the audio; Inserted or Copied ends and snoozes | Code (`Overlay.release`); emulator | ✅ |
| Dictation ends with a finger on the bubble: result at full size, 3 s count from release | Code (`LiveBubble` rest timer keyed on touch); emulator | ✅ |
| Every state over light and dark apps, every shape, three sizes | Screenshot `OverlayScreenshotTest.overContent`, `looks`; `ShapesTest` | ✅ |
| Real dictation to Google, end to end | 👤 1 | 👤 |

## Geometry, docking, motion

| Case | Coverage | Status |
|---|---|---|
| Docked Smaller or Dot flush 4 dp to its edge, same height, every size | Unit `GeometryTest.dockedIsFlushToItsEdgeAtTheSameHeightWhateverTheSize` | ✅ |
| The full bubble's centre never moves with size, look, opacity | Unit `GeometryTest.theFullBubblesCentreNeverMoves` | ✅ |
| Nothing off screen or under bars and cutouts, portrait and landscape | Unit `GeometryTest.nothingLeavesTheScreenOrGoesUnderBarsOrCutouts` | ✅ |
| Touch area at least 48 dp, holding the bubble | Unit `GeometryTest.theTouchAreaIsAtLeast48dpAndHoldsTheBubble` | ✅ |
| The bubble's window never resizes | Unit `GeometryTest.theWindowNeverChangesSize` | ✅ |
| Full ↔ idle is one interpolation; size and opacity move together, one way | Unit `GeometryTest.theTransitionIsOneInterpolation`, `RestTransitionTest` | ✅ |
| Rotation keeps the side and the share of the height | Unit `GeometryTest.rotationKeepsTheSideAndTheShareOfTheHeight` | ✅ |
| Drag keeps it on screen; release docks at the nearer edge, at the drop height | Unit `GeometryTest.aDragKeepsTheBubbleOnScreenAndDropsWhereItWas`; emulator recording | ✅ |
| Label comes and goes: the bubble doesn't move by a pixel, either side | Emulator `tools/emulator/bubble-stays.sh pill right|left` | ✅ |
| Size changed while docked: the dot doesn't move | Emulator `tools/emulator/bubble-stays.sh resize` | ✅ |
| Tap a docked dot: dictation starts at once | Emulator (tap inside the 48 dp strip) | ✅ |
| Drag from the docked dot at the screen edge isn't taken by the back gesture | 👤 6 | 👤 |
| Animations removed in Android's settings: everything lands at once | Code (`MotionDurationScale` on the overlay's scope; Compose follows the setting) | ✅ |

## Visibility

| Case | Coverage | Status |
|---|---|---|
| Always / While typing; a dictation always shows | Unit `VisibilityTest.eachModeShowsWhereItShould`, `aDictationShowsEvenSnoozed` | ✅ |
| ✕ snooze: back at the next unlock (Always) | Unit `VisibilityTest.alwaysWakesOnlyAtTheNextUnlock`; emulator (swipe lock) | ✅ |
| ✕ snooze, While typing: back at another field, or the same after focus left | Unit `VisibilityTest.whileTypingWakesAtAnotherField`, `…AtTheSameFieldAfterFocusLeft`, `snoozedWithNoFieldWakesAtAnyField`; emulator | ✅ |
| Fields compared across nodes on Android 12 (no `equals` before 13) | Code (`FieldKey`: window, id, bounds) | ✅ |
| Use Burble off: overlay gone; during Listening it stops so the words land | Code (`InsertionService`); emulator (switch on home) | ✅ |
| Use Burble on clears a snooze | Code (`setOverlay`) | ✅ |
| Unsent dictation recovered while Use Burble is off: waits, shows once on | Code (`Dictation.recover`, overlay follows the state) | ✅ |
| Lock screen hides the bubble; keyboard never kept hidden there | Code (`updateLock`); 👤 2 | 👤 |
| Password fields: not a target, not a text field for While typing | Code (`focusedField`) | ✅ |

## Insertion targets

| Case | Coverage | Status |
|---|---|---|
| EditText, Compose TextField (state and value), Chrome textarea, contenteditable, address bar, Messages | Emulator (`DebugFieldsActivity`, local web page, Messages) | ✅ |
| The last field tapped during the dictation gets the words, else the start field | Unit `TargetsTest`; emulator `tools/emulator/where-text-goes.sh tap` (EditText start, Compose field tapped) | ✅ |
| Focus an app moves by itself isn't a tap; a first tap sends only focus, so focus within 1.5 s of a touch outside the bubble counts | Code (`InsertionService.tappedField`); emulator `where-text-goes.sh tap` | ✅ |
| Started in app A, switched to B, stopped → clipboard; back in A, nothing lands | Emulator `tools/emulator/where-text-goes.sh switch` | ✅ |
| Tapping a Chrome web field during a dictation | 👤 8 | 👤 |
| Field rebuilt when the keyboard hides: found again by focus, id, only field, or edges at or below | Unit `FindAgainTest`; emulator | ✅ |
| Field that drops focus when the keyboard hides | Emulator (`DebugFieldsActivity`) | ✅ |
| Claude app, Messages with hide-keyboard on, on the phone | 👤 1 | 👤 |

## Settings, onboarding, languages

| Case | Coverage | Status |
|---|---|---|
| Onboarding: microphone, accessibility, file-install unlock, 200 % font | Screenshot `ScreenshotTest.welcome`, `insertion`, `unlock`, `unlockAppInfo`, `unlockLargeFont`; emulator recording | ✅ |
| Try it: the field has focus on arrival, the real bubble's text lands in it, Skip then Done; a failed dictation still offers Done; 200 % font | Screenshot `ScreenshotTest.tryIt*`; emulator: fake result lands, rotation mid-dictation still lands in the field, errors, Done never shows it again | ✅ |
| Try it with a real dictation | 👤 7 | 👤 |
| Sideloaded install: the restricted-settings unlock works | 👤 3 | 👤 |
| Home states: setup, stopped, off, update, 200 % font | Screenshot `ScreenshotTest.home*` | ✅ |
| Preview pinned above its controls while they scroll | Emulator recording | ✅ |
| Keyboard languages: current first, then enabled, deduplicated; none → phone's | Unit `KeyboardLanguagesTest`; emulator (app locale French still reads Gboard's English) | ✅ |
| A keyboard with two languages on the phone | 👤 5 | 👤 |
| Custom languages: primary, at least one | Screenshot `ScreenshotTest.languages` | ✅ |

## Updater and report

| Case | Coverage | Status |
|---|---|---|
| Version comparison | Unit `VersionTest` | ✅ |
| Download, signature check and install of a real release | 👤 4 | 👤 |
| Report: app names removed unless kept; preview before anything leaves | Unit `ReportTest`; screenshot `ScreenshotTest.report` | ✅ |

## On the phone

1. Dictate into the Claude app and into Messages with "Hide keyboard during dictation" on: the words land in the field you started in.
2. Set an alphanumeric screen lock, start a dictation, lock the phone: the lock screen's keyboard shows; after unlock, the dictation's keyboard is hidden again.
3. Install the APK from a file on a phone that never had Burble: the three unlock steps lead to the switch working.
4. Install an update from the Dogfood release through the app.
5. Enable two keyboard languages in Gboard: Advanced shows both under "From your keyboard"; dictate in the second.
6. With Dot docked at the edge: a drag that starts on the dot moves it; Android's back gesture doesn't take it.
7. On a fresh install, finish onboarding to Try it: tap the bubble, say a sentence, tap again; it lands in the field. Rotate the phone mid-sentence once.
8. Start a dictation in Chrome's address bar, tap a text box on the page, stop: the words land in the text box.
9. Dictate a short sentence and tap to stop as you finish: the words land in about half a second, as fast as in Gboard's own dictation.
