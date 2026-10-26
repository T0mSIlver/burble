<p align="center"><img src="docs/icon.png" width="128" alt="Burble icon, a wave inside a cookie shape"></p>

# Burble

Google's Rambler dictation on any Android phone, under any keyboard, from a floating bubble. Free and open source.

<!-- Demo video or GIF: reviewing a website while talking, keyboard hidden, then a tap and the whole review lands in the field. -->

> [!IMPORTANT]
> **Burble exists because of [PixelBoard](https://github.com/Akshayykadam/PixelBoard) by Akshay Kadam.**
> PixelBoard proved that Rambler, the Gemini dictation in Gboard on Pixel phones, works on any Android phone.
> Burble's protocol work began by reading it. PixelBoard 1.3.0, Gboard 18.5.3 with Akshay's patches, showed the class where Rambler's results arrive, and Burble traced them from there back to Google's speech server.
> PixelBoard in turn builds on JasonWu's original Gboard patch project and its reverse engineering.
> If you want Rambler inside a full Gboard keyboard, use PixelBoard.

## Why

Gboard's Rambler dies when scrolling closes the keyboard, and when the screen auto-rotates as you hold the phone to your mouth. Everything you said is gone. I once lost three to four minutes of journaling about a busy day to one rotation.

> When I dictate, in no situation do I want a keyboard on the screen, whether I'm looking at it or not.

## How it works

1. Tap the bubble and talk. The keyboard hides while you dictate, so the screen stays free for what you're reading.
2. Burble streams your voice to Google's speech server, the one Gboard's Rambler uses. Scrolling, rotating the phone or switching apps doesn't stop it.
3. Tap again. The text lands in the field you started in. If that field is gone, the text waits in the bubble for the next field you focus, and goes on the clipboard.

<!-- Screenshots: the bubble listening over an agent's answer; the bubble with text ready to paste. -->

## Install

Burble needs Android 12 or later.

1. Download `burble-<version>.apk` from [the latest release](https://github.com/T0mSIlver/burble/releases/latest), or add `https://github.com/T0mSIlver/burble` to [Obtainium](https://github.com/ImranR98/Obtainium).
2. Open Burble, tap **Allow microphone**, then turn on Burble in Accessibility settings. Accessibility is the only way Android lets an app type into other apps.

On Android 13 and later, Android may block the accessibility switch for an app installed from a file. Burble shows these steps when that happens:

1. In Accessibility settings, tap Burble, then **Close**.
2. Open Burble's App info, tap **⋮**, then **Allow restricted settings**.
3. Back in Accessibility settings, turn on Burble.

Every release is signed with this certificate. Check its SHA-256 with `apksigner verify --print-certs burble-<version>.apk` or [AppVerifier](https://github.com/soupslurpr/AppVerifier):

```text
3A:7A:AB:26:6C:F0:04:11:69:79:DA:DB:31:57:9F:1A:A9:FA:56:17:D5:C2:CD:7C:18:1D:C8:02:80:33:60:73
```

## What leaves your phone

Google's speech service, the one Gboard uses, receives the audio you dictate, your dictation languages, your phone's model and Android build, and the name of the app you're dictating into. There is no Burble server, account, analytics or tracking, and you don't need a Google account.

Burble also talks to GitHub. It checks for a new release once a day when you open it, unless you turn off **Check for updates**. **Report a problem** opens a GitHub issue with your phone model and Burble's recent log, which you read and edit before sending. The log names the app when an insert falls back to the clipboard, and holds no dictated text.

The microphone records only while you dictate. The audio stays on the phone until your text is delivered, then Burble deletes it. A failed dictation keeps its audio for a retry until you drop it or start the next one. Burble keeps no history.

The accessibility service shows the bubble, hides the keyboard while you dictate, and writes your words into the field you started in. It keeps nothing it reads once the dictation ends.

## Disclaimer

Burble is an independent project. It is not affiliated with, endorsed by or sponsored by Google, and it contains no Google code.

Burble calls a Google speech service that Google has not opened to third-party apps, and identifies itself with the same fixed headers Gboard sends. Google can change, block or rate-limit that service at any time, and Burble will then stop working without notice. Using it may breach that service's terms.

Google, Gboard, Gemini and Android are trademarks of Google LLC. They appear here only to say what Burble works with. Burble comes with no warranty. Don't dictate what you may not share with Google.

## Building

```bash
export ANDROID_HOME=<sdk>   # platform android-37, build-tools 36+
./gradlew assembleDebug
```

Gradle 9.8, AGP 9.4 with built-in Kotlin, Java 21.

## Licence

Copyright 2026 Tom Vaucourt. [GPL-3.0](LICENSE). The app is set in Google Sans Flex, under the [SIL Open Font License](licenses/GoogleSansFlex-OFL.txt).
