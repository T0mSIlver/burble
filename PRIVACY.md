# Privacy

Burble has no server of its own. The developer receives nothing from
the app.

## What leaves your phone

While you dictate, the app sends to `speechs3proto2-pa.googleapis.com`
(Google):

- the microphone audio, from when you tap the bubble until you tap again;
- the device model (`Build.MODEL`) and the Android build string
  (`Build.DISPLAY`);
- fixed request fields: language `en-US`, the platform name `Android`, and
  the Gboard and Google app identifiers the backend expects;
- three static headers: the Google app's API key, package name and certificate
  hash.

Google processes this audio under its own terms and privacy policy, which this
project does not control. The text you get back comes from that service.

**Send test audio** sends a bundled clip the same way and no microphone audio.

**Report a problem** opens a GitHub issue in your browser, prefilled with the
app version, phone model, Android version, dictation languages and Burble's
recent log lines. Those lines hold no dictated text, but they name the app
where an insert fell back. Nothing is sent until you submit the issue, and you
can edit it first.

## What does not leave your phone

- No account, sign-in or advertising ID.
- No analytics, crash reporting or telemetry.
- No connection to any host other than the Google endpoint above.

## What the accessibility service reads

The service handles no accessibility events. Only when a dictation finishes
does it look up the focused editable field in the active window. It reads that
field's text and cursor position, so it can insert the dictated words at the
cursor, and it writes the new text back. It does not read other fields, other
windows or what is on screen, and it does not log keystrokes.

If no editable field has focus, the dictated text is copied to the clipboard
instead.

## What is stored

The app stores no dictation, audio or text. Nothing is written to disk. The
debug build writes protocol lines to the Android system log (`S3DBG`) on the
device. Those lines can include server responses, so treat the log as
sensitive.
