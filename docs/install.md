# Install

Burble needs Android 13 or later.

1. Download `burble-<version>.apk` from [the latest release](https://github.com/T0mSIlver/burble/releases/latest), or add `https://github.com/T0mSIlver/burble` to [Obtainium](https://github.com/ImranR98/Obtainium).
2. Open Burble, tap **Allow microphone**, then turn on Burble in Accessibility settings. Accessibility is the only way Android lets an app type into other apps.

## If Play Protect asks to scan the app

Play Protect hasn't seen a new release before, so it may recommend a scan. **Scan app** sends the APK to Google first. To skip the scan, tap **More details**, then **Install without scanning**.

## If Android blocks the accessibility switch

Android may block it for an app installed from a file. Burble shows these steps when that happens:

1. In Accessibility settings, tap Burble, then **Close**.
2. Open Burble's App info, tap **⋮**, then **Allow restricted settings**.
3. Back in Accessibility settings, turn on Burble.

## Check the APK

Every release is signed with this certificate. Check its SHA-256 with `apksigner verify --print-certs burble-<version>.apk` or [AppVerifier](https://github.com/soupslurpr/AppVerifier):

```text
3A:7A:AB:26:6C:F0:04:11:69:79:DA:DB:31:57:9F:1A:A9:FA:56:17:D5:C2:CD:7C:18:1D:C8:02:80:33:60:73
```
