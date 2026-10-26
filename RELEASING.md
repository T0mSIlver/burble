# Releasing

Until GitHub Actions runs for this repository, releases are built and signed on the dev box. The
release key lives in `/path/to/release-secrets/` (alias `burble`; the store and key
share one password) and never enters the repository. A release build without it fails.

Build, test, lint and sign version 1.2.3 in one command:

```bash
S=/path/to/release-secrets
BURBLE_KEYSTORE=$S/burble-release.jks BURBLE_KEY_ALIAS=burble \
  BURBLE_KEYSTORE_PASSWORD="$(cat $S/release-keystore-password)" \
  BURBLE_KEY_PASSWORD="$(cat $S/release-keystore-password)" \
  ./gradlew --no-daemon -PversionName=1.2.3 testDebugUnitTest lintRelease assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Check it is signed with the certificate
in the README, then publish it as `burble-1.2.3.apk`, which is what the in-app updater and Obtainium
download:

```bash
cp app/build/outputs/apk/release/app-release.apk burble-1.2.3.apk
"$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner" verify --print-certs burble-1.2.3.apk
git tag v1.2.3 && git push origin v1.2.3
gh release create v1.2.3 burble-1.2.3.apk --title "Burble 1.2.3" --generate-notes
```

Once Actions runs, pushing the tag does all of this in `.github/workflows/release.yml`.
