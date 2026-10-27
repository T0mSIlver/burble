# Edge cases

Every shipped feature's cases, how each is covered, and its status. **Unit**: a JVM test in
`app/src/test`. **Screenshot**: a Roborazzi test writing `app/screenshots`. **Emulator**: a script
in `tools/emulator` or a scripted run on the `sb36` emulator with the debug build's hooks
(`DebugReceiver`). **Pixel**: by hand on a phone; the list at the end.

Status: ✅ covered and passing, 👤 needs the phone, ❌ fails, open. **Lab**: an instrumented test in
`app/src/androidTest` (docs/testlab.md), on an emulator and Firebase Test Lab's phones.

## Bubble states

| Case | Coverage | Status |
|---|---|---|
| Tap → Listening → tap or silence → Finishing → Inserted (check 0.7 s) | Unit `DictationSessionTest.stopsAfterSilence`; emulator recording `motion-r1` | ✅ |
| The text lands 0.5 s after the stop (Gboard on a Pixel: 0.7 s): each 64 ms of audio goes out as its own Ogg page, one connection serves every dictation, and the text goes in on the final result, not when the stream closes | Unit `EncoderBackpressureTest.eachBufferGoesOutAsAPage`; emulator `tools/emulator/latency.sh` (535 to 551 ms from the stop to the insert) | ✅ |
| The network changes between two dictations: the second opens a new connection instead of waiting on the dead one | Emulator: airplane mode on and off, then `latency.sh` | ✅ |
| No field, or not on screen → clipboard, Copied (the idle look at once, a haptic, TalkBack says it), nothing inserted later | Unit `TargetsTest`; emulator `tools/emulator/where-text-goes.sh switch` | ✅ |
| Keyboard hidden only in Listening and Finishing; back as the words land | Code (`followDictation`, `showKeyboard`); emulator `tools/emulator/keyboard-back.sh` | ✅ |
| Keyboard back in an EditText, a Compose field, Chrome's address bar, a Chrome page's textarea and Messages: started in the field, a field tapped mid-dictation (its app takes the keyboard for shown: focus taken for a frame), clipboard with a field focused; none without one | Emulator `tools/emulator/keyboard-back.sh` (0.4 to 0.7 s, the keyboard's own animation included) | ✅ |
| Error: the refresh arrow and its words in a panel beside the bubble until tap, ✕ or the next dictation while its audio waits; otherwise the idle look, words 4 s (nothing heard 2 s) | Code (`LiveBubble`); screenshot `OverlayScreenshotTest.looks` | ✅ |
| Failed with audio kept → retry sends the same audio, only the failed segment | Unit `DictationSessionTest.retrySendsTheSameBytes`, `retryResendsOnlyTheFailedSegment` | ✅ |
| Nothing heard → the idle look at once, its words 2 s; no microphone → the same, words 4 s | Screenshot `OverlayScreenshotTest.looks`; emulator recording | ✅ |
| Process stopped mid-dictation → "Dictation not sent" comes back, sends again | Unit `DictationSessionTest.aStoppedProcessLeavesAudioThatSendsAgain`, `nothingToRecoverFromAnEmptyFolder` | ✅ |
| Dictation past 5 minutes chains server sessions, no audio lost | Unit `DictationSessionTest.longDictationChainsWithoutLosingAudio`, `switchesAtTheFirstQuietBufferInTheWindow` | ✅ |
| Encoder busy or hung: no audio lost, no hang | Unit `EncoderBackpressureTest` | ✅ |
| Tap during Finishing does nothing; right after Inserted or Copied it starts listening | Code (`tapBubble`); emulator | ✅ |
| Finishing: the light settles; Gboard's sweep only from 700 ms, so a quick finish shows none; with Remove animations, the settled light dimmed, no sweep | Screenshot `OverlayScreenshotTest.looks` (0.3, 1.2, 2.2 s; reduced motion) | ✅ |
| ✕ during Listening or Finishing cancels; Error drops the audio; Inserted or Copied ends and snoozes | Code (`Overlay.release`); emulator | ✅ |
| Dictation ends with a finger on the bubble: result at full size, 2 s count from release | Code (`LiveBubble` rest timer keyed on touch); emulator | ✅ |
| Every state over light and dark apps, every shape, three sizes | Screenshot `OverlayScreenshotTest.overContent`, `looks`; `ShapesTest` | ✅ |
| Real dictation to Google, end to end | 👤 1 | 👤 |
| Haptics: a composition per event (start, stop, inserted, copied, error, nothing heard, ✕, snap to the edge); a missing primitive falls back to the system effect; THUD missing → two low ticks; Android 12's ✕ is a clock tick | Unit `HapticsTest`; debug row "Haptics test (temporary)" under Advanced | ✅ |
| Haptics off in the system's touch feedback: none plays | Code (`Haptics.play`: USAGE_TOUCH on 13+, the setting read on 12); 👤 11 | 👤 |
| Nothing vibrates while listening but the start (the drop on ✕ ends the dictation, so it plays) | Code (`Haptics.play`) | ✅ |
| ✕ target: tonal at rest, grows 15 % and deepens with the bubble over it, never red, light and dark | Screenshot `OverlayScreenshotTest.dismissTarget` | ✅ |

## Geometry, docking, motion

| Case | Coverage | Status |
|---|---|---|
| Docked Smaller or Dot flush 4 dp to its edge, same height, every size | Unit `GeometryTest.dockedIsFlushToItsEdgeAtTheSameHeightWhateverTheSize` | ✅ |
| The full bubble's centre never moves with size, look, opacity | Unit `GeometryTest.theFullBubblesCentreNeverMoves` | ✅ |
| Nothing off screen or under bars and cutouts, portrait and landscape | Unit `GeometryTest.nothingLeavesTheScreenOrGoesUnderBarsOrCutouts` | ✅ |
| Touch area at least 48 dp, holding the bubble | Unit `GeometryTest.theTouchAreaIsAtLeast48dpAndHoldsTheBubble` | ✅ |
| The bubble's window never resizes | Unit `GeometryTest.theWindowNeverChangesSize` | ✅ |
| Full ↔ idle is one interpolation on the spatial spring; size, dock, opacity together; no frame jumps; overshoot stays on screen and in the window | Unit `GeometryTest.theTransitionIsOneInterpolation`, `nothingLeavesTheScreenOrGoesUnderBarsOrCutouts`, `RestTransitionTest`; frames every 16 ms `DockFramesTest`; emulator `tools/emulator/dock-anim.sh` | ✅ |
| The Dot morphs into a circle, its shadow with it; the glyph fades out halfway, with no frame where it drops | Frames `DockFramesTest` | ✅ |
| A motion frame redraws the bubble without recomposing it | Code (`Placement` read while drawing); 12 recompositions over 122 frames, 172 before | ✅ |
| A touch or scroll in the app underneath rests the idle bubble at once; repeated ones don't restart it | Unit `RestsEarlyTest`; emulator `TRIGGER=scroll tools/emulator/dock-anim.sh` | ✅ |
| Never early during a dictation, a result or an error, with a finger on the bubble, or within 0.5 s of one leaving it | Unit `RestsEarlyTest` | ✅ |
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
| Burble's own screens: shown with Always (home, Bubble look, Advanced, About), clear of the main switch and back at its default place; drags; Bubble look changes it live; no bubble in onboarding before Accessibility is on | Unit `VisibilityTest.eachModeShowsWhereItShould`; emulator (sb66: screenshots of each page, a drag on home, Dot picked on Bubble look) | ✅ |
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
| A field the app focused on opening, never tapped (EditText in onCreate, Compose FocusRequester as in chat apps), keyboard down: the start field; found in the active window, any window, inside a focused container, or from the app's focus event | Unit `TargetsTest.anAppFocusedFieldIsTheStartField` and three more; emulator `tools/emulator/where-text-goes.sh autofocus-edit`, `autofocus-compose` | ✅ |
| A field the app focused after the start, not tapped: clipboard; the next dictation there lands in it | Emulator `where-text-goes.sh autofocus-late` | ✅ |
| The Claude app's message box focused on opening a chat | 👤 10 | 👤 |
| Tapping a Chrome web field during a dictation | 👤 8 | 👤 |
| Field rebuilt when the keyboard hides: found again by focus, id, only field, or edges at or below | Unit `FindAgainTest`; emulator | ✅ |
| Field that drops focus when the keyboard hides | Emulator (`DebugFieldsActivity`) | ✅ |
| Rotation mid-dictation over an app that keeps its window | Lab `DictationTest.survivesRotationMidDictation` | ✅ |
| Rotation mid-dictation over an app recreated on rotation (most apps): its field is in a new window, which `InsertionService.current` refuses, so the words go to the clipboard | Lab `DictationTest.survivesRotationOfARecreatedApp` (`@Ignore` until decided, RMB-29) | ❌ |
| Another app's field, the stock messaging or contacts app with the phone's own keyboard | Lab `DictationTest` | ✅ emulator, Test Lab waits on Tom |
| Claude app, Messages with hide-keyboard on, on the phone | 👤 1 | 👤 |

## Gboard handoff (debug builds only)

A patched Gboard's mic key starts Burble's dictation (Burble's design notes spec/handoff.md). Emulator runs use `HandoffDebug`, which lets adb's shell stand in for the keyboard.

| Case | Coverage | Status |
|---|---|---|
| Only the default keyboard may start, only with a text field focused; any other app, a keyboard that isn't the default, or no field is refused | Unit `HandoffTest` | ✅ |
| `start` is a bubble tap: same dictation, same insertion, keyboard hidden; accepted only if a dictation is under way after it (stops one listening, retries one whose audio waits); no microphone → refused, so the keyboard falls back to its own | Code (`HandoffProvider.start`); emulator `content call … start` (accepted, `state` says listening, words land) | ✅ |
| Release builds: the provider is disabled (`@bool/handoff` false), so the keyboard finds no Burble and keeps its own mic | Code (`BuildConfig.HANDOFF`, `resValue`) | ✅ |
| After `hello` from the default keyboard, the bubble hides while the keyboard is up and shows once it's gone; a dictation always shows | Unit `VisibilityTest.aKeyboardThatHandsOffHidesItButNotADictation`; emulator screenshots | ✅ |
| The bubble comes out of the key as a copy of its disc (fill, glyph, no shadow), rises to the corner 8 dp from the edge and above where the keyboard's top was, the light fading in mid-flight (material/images/handoff-motion/handoff-motion.md) | Emulator recording (material/images/handoff-motion/app-flow.*); the key's glyph needs the patch's `keyIcon` | ✅ |
| The words land: the light goes out, no mic; the bubble waits at its spot, and 60 ms after the keyboard comes (its window or its hello) flies back into the key, takes its fill and glyph, and hands over at 400 ms (`hello` returns `holdKeyUntil`); hidden while the keyboard stays up, fades in at home once it goes | Emulator recording; code (`Overlay.keyboardBack`, `land`) | ✅ |
| The flight draws in a window of its own, the whole screen, kept while a keyboard that hands off is up, the key's copy predrawn at the key all but transparent; the bubble's window takes over at rest, a frame or two of overlap each way | Code (`Overlay.flightView`, `keySeen`, `toOwnWindow`); emulator recording | ✅ |
| `start` draws the key's copy first, then starts the dictation and answers; the copy holds over the key until then, so the key and the bubble never show apart. Tap → first bubble frame on the emulator (load average 70 to 180 on the host): 32 to 145 ms, median about 50; the first tap after an install too (61 ms), the light drawn once unseen at the first hello | Log `Handoff: first bubble frame … ms after the tap`, `start answered …` | ✅ |
| The keyboard's window blinking out as it shows doesn't show the bubble (gone only after 200 ms); Burble doesn't take focus to retry a keyboard that said hello since its ask | Code (`InsertionService.followKeyboard`, `watchKeyboard`) | ✅ |
| No keyboard comes back: copied, or no field to bring it to → home at once; otherwise home, docked at once, 250 ms after Burble's last ask for the keyboard (it asks again 0.7 s after a click that didn't bring it) | Code (`Overlay.flyOn`, `noKeyboard`, `keyboardAsked`); emulator (the first click never brings PixelBoard back there; the second does) | ✅ |
| Nothing heard: its words at the spot, then the return | Code (`Overlay.flyOn` on Idle) | ✅ |
| With animations removed: no travel; it appears at the spot and goes at once, the key shown at once | Code (`Overlay.reducedMotion`) | ✅ |
| Dragged mid-flight, or the screen turns: the flight ends where it is; the bubble docks as after any drag | Code (`Overlay.endFlight`) | ✅ |
| With the patched Gboard (PixelBoard 18.5.3, burble-handoff build) on the emulator: hello hides the bubble, a tap on its mic key starts, the words land, the bubble flies back into the key | Emulator `sb36` (material/images/handoff-motion/app-flow.*) | ✅ |
| With the patched Gboard on the phone | 👤 12 | 👤 |

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
10. Open a chat in the Claude app without tapping its message box, tap the bubble, say a sentence, tap again: the words land in the box. If they go to the clipboard, send a report from About: its log says why ("insert fell back: …").
11. Feel each event in the debug build's "Haptics test (temporary)" row under Advanced; then turn Touch feedback off in Settings and check none plays.
12. With the patched Gboard (debug Burble): tap its mic key in Messages. The bubble rises out of the key as the keyboard goes; speak; tap the bubble. The words land, the bubble flies back into the key as the keyboard returns, and no bubble shows while the keyboard is up.
