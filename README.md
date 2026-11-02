<p align="center"><img src="docs/icon.png" width="128" alt="Burble icon, a wave inside a cookie shape"></p>

# Burble

Google's Rambler dictation on any Android phone, under any keyboard, from a floating bubble. Free and open source.

<!-- Demo video or GIF: reviewing a website while talking, keyboard hidden, then a tap and the whole review lands in the field. -->

> [!IMPORTANT]
> **Burble exists because of [PixelBoard](https://github.com/Akshayykadam/PixelBoard) by Akshay Kadam**. It proved Rambler works on any Android phone, and Burble's reverse engineering started from its build and the Gboard code it patches, which pointed straight to Rambler's code. PixelBoard builds on [JasonWu's original Gboard patch project](https://github.com/jasonwu1994/Gboard-patches). If you want Rambler inside a full Gboard keyboard, use PixelBoard.

The bubble's behaviour takes ideas from Wispr Flow's Android app.

## How it works

1. Tap the bubble and talk. The keyboard hides, and scrolling or rotating the phone doesn't stop you.
2. Tap again. Your words land in the last field you tapped while talking, else the one you started in; otherwise they go to the clipboard.

<!-- Screenshots: the bubble listening over an agent's answer; the light settling as the words land. -->

## Install

Android 12 or later. Download the APK from [the latest release](https://github.com/T0mSIlver/burble/releases/latest), or add `https://github.com/T0mSIlver/burble` to [Obtainium](https://github.com/ImranR98/Obtainium) for updates, then open Burble and follow its two steps. Details, and how to check the APK's signature: [docs/install.md](docs/install.md).

## What leaves your phone

Your dictated audio goes to Google's speech server, the one Gboard uses. There is no Burble server, account or tracking. [Everything Burble sends and keeps](docs/privacy.md).

Questions: [FAQ](docs/faq.md).

## Disclaimer

Burble is not affiliated with Google and contains no Google code. It uses a Google speech service that isn't open to third-party apps, so it may stop working at any time, and using it may breach that service's terms. Google, Gboard, Gemini and Android are trademarks of Google LLC. No warranty.

## Licence

Copyright 2026 Tom Vaucourt. [GPL-3.0](LICENSE). Bundled font and libraries: [licenses/](licenses/). [Building](docs/building.md).
