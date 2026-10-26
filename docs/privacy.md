# What leaves your phone

Google's speech service, the one Gboard uses, receives the audio you dictate, your dictation languages, your phone's model and Android build number, and the package name of the app you're dictating into, which Gboard sends too. There is no Burble server, account, analytics or tracking, and you don't need a Google account.

Burble also talks to GitHub. It checks for a new release once a day when you open it, unless you turn off **Check for updates**. **Report a problem** first shows the whole report on your phone: your phone model, Android version, languages and Burble's recent log, which holds no dictated text. App names in the log are replaced unless you keep them. Nothing goes to GitHub until you tap **Open GitHub issue**, and you can still edit it there.

The microphone records only while you dictate. The audio stays on the phone until your text is in a field or on the clipboard, then Burble deletes it. Until then it is kept: for a dictation that failed to send, and one cut short when Android stopped Burble. You drop it by dragging the bubble to ✕. Burble keeps no history.

The accessibility service shows the bubble, hides the keyboard while you dictate, and writes your words into the last field you tapped while dictating, else the one you started in. To do that it follows which field has focus, which window is in front, whether the keyboard shows and, while you dictate, when a finger touches the screen (not where), and it reads a field's text only to insert yours into it. Switched off, it follows nothing. It keeps nothing it reads.
