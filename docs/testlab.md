# Device lab: Burble on phones other than a Pixel

Burble has only run on a Pixel. What other vendors can break is not the dictation (the server
does that) but how Android is bent around it: the bubble drawing over apps, the accessibility
service typing into other apps' fields (Samsung Keyboard, the stock messaging app), the
restricted-settings unlock, the microphone's foreground-service rules, and the vendor stopping
apps in the background. Firebase Test Lab runs the instrumented tests in
`app/src/androidTest/java/dev/silver/burble/lab` on real phones and keeps a video and logcat of
each (Plane RMB-29).

## The tests

The debug build hears `assets/clip.wav` (the first 4.3 s of the Test connection clip) instead of
the microphone, and can take its text from a stand-in instead of the server
(`src/debug/.../DebugAudio.kt`; release builds have neither). Every test starts dictation the way a
user does: a tap on the bubble.

| Test | What it checks |
|---|---|
| `DictationTest.landsInAnotherAppsField` | Tap the bubble over another app's field (the test APK's own `HostActivity`): the words land in it. With `server on`, the clip goes to Google's server: **the only request of the run** |
| `DictationTest.landsInTheStockMessagingApp` | The same in the phone's own messaging app (its compose box) with its own keyboard; a new contact's name where there is no messaging app |
| `DictationTest.survivesRotationMidDictation` | The screen turns while listening, over an app that keeps its window: the words still land, the bubble stays on screen |
| `DictationTest.survivesRotationOfARecreatedApp` | The same over an app recreated on rotation, as most are: the words land in its field in the new window |
| `OverlayTest.showsDocksAndComesBack` | The bubble shows, docks to the edge 2 s after the last touch, is full size while listening, docks again after |
| `BackgroundTest.survivesAMinuteInTheBackground` | A minute on the home screen with the screen off: same process, the accessibility service still bound, the next dictation lands |
| `BackgroundTest.microphoneServiceIsNotSilenced` | The real microphone, opened from a tap over another app: the foreground service has the microphone type and Android doesn't silence the recording. Nothing is sent |
| `RestrictedSettingsTest.unlockThroughAppInfo` | Android 13+: with the restriction a file install gets, the Accessibility switch is refused; App info → ⋮ → Allow restricted settings unlocks it, then the switch takes. Skipped where Settings ignores the restriction for an adb install |

Permissions and the accessibility service are set up as Test Lab allows, through adb's shell
(`pm grant`, `settings put secure enabled_accessibility_services`), not through the onboarding
screens. Every test saves screenshots to `/sdcard/Android/data/dev.silver.burble/files/lab`, which
Test Lab pulls.

On an emulator (no request to Google's server unless `-e server on`):

```bash
./gradlew connectedDebugAndroidTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.silver.burble.lab.OverlayTest
```

## The phones

`tools/testlab.sh` runs five, chosen from `gcloud firebase test android models list` (the
catalogue changes; the script skips a model that's gone and says so):

| Model ID | Phone | Android | Why |
|---|---|---|---|
| `e1q` | Samsung Galaxy S24 | 16 (One UI 8) | Samsung flagship |
| `a16x` | Samsung Galaxy A16 5G | 16 (One UI 8) | Samsung's best-selling budget line, little memory |
| `OP573DL1` | OPPO CPH2557 | 14 (ColorOS) | Stands in for Xiaomi: Test Lab's only Xiaomi is a Redmi 6A on Android 8.1, below Burble's Android 13. ColorOS is as eager to stop background apps |
| `CPH2449` | OnePlus 11 5G | 14 (OxygenOS) | OnePlus |
| `cuscoi` | Motorola edge 50 fusion | 15 | Motorola |

Change them with `DEVICES="model:API …" tools/testlab.sh`.

## What Tom does, once

1. **A Firebase project on the free Spark plan.** [console.firebase.google.com](https://console.firebase.google.com) → Add project → name it `burble-lab` (no Google Analytics needed). Spark is the default; don't add billing. Note the project ID it shows.
2. **gcloud.** `brew install --cask gcloud-cli` (Google Cloud SDK).
3. **Log in and pick the project.**
   ```bash
   gcloud auth login
   gcloud config set project <project-id>
   ```
   The first run asks to enable the Cloud Testing API and the Cloud Tool Results API: say yes (free).
4. **Run it.**
   ```bash
   cd ~/work/burble
   export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=/opt/homebrew/opt/openjdk@21
   tools/testlab.sh
   ```
   About 10 to 15 minutes; the phones run in parallel. `SERVER=off tools/testlab.sh` sends nothing
   to Google's server (the text then comes from the stand-in).

## Cost and quotas

Spark (no card): **5 runs a day on physical phones** (each phone in the matrix is one run), 10 on
virtual ones. The five-phone matrix uses the whole day's quota in one go; a second run the same
day fails with a quota error, it doesn't bill. Results are kept in the project's default Cloud
Storage bucket, free at this size.

Blaze (pay as you go), only if Spark's quota is too tight: 30 minutes a day on physical phones
free, then $5 per phone-hour, billed by the minute. One run of this matrix is about 5 × 10 minutes,
roughly $2–3 beyond the free half hour. Not needed for now.

Google's server: with `server on` (the default for `tools/testlab.sh`), one 4.3 s clip per phone
per run, as a user's single dictation. Nothing else in the suite reaches it.

## Reading the results

- The script prints a table per phone (Passed, Failed, Skipped, Inconclusive) and a link to the
  Firebase console: Test Lab → `burble-lab` history → the run → a phone. Each phone's page has the
  video, logcat, the test cases, and the pulled screenshots under Test results → Files.
- It also copies everything to `build/testlab/<date-time>/`: per phone, `video.mp4`, `logcat`,
  `test_result_*.xml`, and `artifacts/sdcard/.../lab/*.png`.
- In logcat, `BurbleLab` lines say the phone, its keyboard, the text each dictation got and where
  it went; `Dictation`, `Insert` and `DebugAudio` are Burble's own.
- A **crash** of the whole run on a phone, mostly during `survivesAMinuteInTheBackground`: the
  vendor stopped Burble's process. A failure there with "accessibility service was stopped": it
  unbound the service only. Both are what users of that phone would see as the bubble vanishing.
- `RestrictedSettingsTest` **skipped**: that phone's Settings didn't restrict an adb install; the
  screenshots still show its screens. **Failed** at "no Allow restricted settings": the menu is
  elsewhere on that skin; look at the `app-info-menu` screenshot and update docs/install.md.
- Limits: the tests run inside Burble's process, which may keep it alive a little better than a
  phone in a pocket for hours; Test Lab's phones have no SIM and no screen lock.
