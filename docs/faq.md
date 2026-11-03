# FAQ

### What does Burble send, and why to Google?

Burble has no speech model of its own. Google's speech server, the one Gboard's Rambler uses, turns your audio into text. There is no Burble server. [What leaves your phone](privacy.md) lists everything Burble sends and keeps.

### How does Burble get in without an account? Does it pretend to be Google's app?

Yes. Google's speech server answers only requests that name a Google app, so Burble sends the same three identifiers Gboard sends: the Google app's package name, the hash of its signing certificate, and the API key that ships inside it. They name an app, not a person, and no account or sign-in is involved. This is why Google can block Burble at any time, and why using it may breach Google's terms.

### Why does it need accessibility?

Android lets an app type into another app's field only through an accessibility service. Burble's service also shows the bubble and hides the keyboard while you dictate. [What leaves your phone](privacy.md) says what it reads.

### Can Google ban my account or block Burble?

Burble never signs in to Google, so it uses no account of yours. Google can block Burble at any time, and dictation in Burble then stops working. Nothing else on your phone changes.

### Does it work on Samsung and other non-Pixel phones?

Burble needs Android 12 or later and uses no Pixel-only part, so the phone's brand shouldn't matter. If it fails on yours, tap **Report a problem** in Burble. It follows your keyboard's languages, or you pick from 34 common ones in **Advanced**, and Google's server decides how well each works.

### Why did a sentence go missing?

Rambler sometimes drops a sentence that sounds like an instruction, such as "keep it minimal", because Google's server takes it as an edit to your text rather than words to write. It does this in Gboard too, and Burble sends Rambler's text as it comes. In 15 of the author's own dictations, 5 lost a phrase this way.

### Android blocks the accessibility switch, or Play Protect asks to scan Burble. What do I do?

For the blocked switch, follow the restricted settings steps in [Install](install.md); Burble shows them too. If Play Protect recommends a scan, **Scan app** checks Burble and then lets you install it; [Install](install.md) says how to skip the scan.

### Android refuses to install it. What can I do?

In some countries, Play Protect blocks installing from a browser, messaging app or file manager any app that asks for an accessibility service, and Burble needs one. Google started this in Singapore and has extended it to more countries, including India, Thailand and Brazil. Installing through [Obtainium](https://github.com/ImranR98/Obtainium) may get past it; that hasn't been tested yet.

### Does it work with Advanced Protection?

No. Android 17's Advanced Protection turns off accessibility services in apps that aren't accessibility tools, and Burble can't insert text without one.

### Is Burble registered with Google's developer verification?

No. Burble is not registered with Google's developer verification. In Brazil, Indonesia, Singapore and Thailand, where the checks started on 30 September 2026, installing it needs `adb install` or Android's one-time "install apps from unverified developers" setting, which takes a day to turn on. Elsewhere it installs normally until Google extends the checks to all countries, planned for 2027.

### Is this the official app?

Burble comes only from [github.com/T0mSIlver/burble](https://github.com/T0mSIlver/burble) and its [Releases](https://github.com/T0mSIlver/burble/releases). It isn't on Google Play. Before installing an APK from anywhere, check it is signed with the certificate in [Install](install.md).

### How is it different from PixelBoard?

[PixelBoard](https://github.com/Akshayykadam/PixelBoard) gives you Rambler inside a full Gboard keyboard, and it showed first that Rambler works on any phone. Burble keeps your keyboard and dictates from a bubble.
