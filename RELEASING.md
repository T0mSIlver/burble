# Releasing

A release is `burble-<version>.apk`, signed with Burble's release key (alias `burble`), attached
to the GitHub release `v<version>` with its SHA-256 in `burble-<version>.apk.sha256`. The in-app
updater, Obtainium and IzzyOnDroid all depend on two things never changing:

- **The signing certificate.** Android installs an update only when it is signed with the same key
  as the installed app, and IzzyOnDroid pins the certificate. Every release must carry the
  certificate in [docs/install.md](docs/install.md). Both paths below check it and stop if it
  differs. A lost or replaced key means every user uninstalls and starts again.
- **The asset name.** One `.apk` per release, named `burble-<version>.apk`, on a tag `v<version>`
  (MAJOR.MINOR.PATCH, minor and patch below 100: versionCode is major×10000 + minor×100 + patch, and
  must grow). The updater takes the release's first `.apk` asset, and Obtainium and IzzyOnDroid
  match the name. The checksum file ends in `.sha256`, so none of them mistake it for the APK.

The release key and its password are in Tom's Vaultwarden, item "Burble Android release keystore"
in "Keys - irreplaceable" (one password for the store and the key), and in the repository's
`BURBLE_*` secrets for CI. They never enter the repository, and only Tom handles them.

## CI: `.github/workflows/release.yml`

Pushing a tag `v1.2.3`:

1. checks the tag is a release version and the four `BURBLE_*` secrets are set, and stops there
   with an error saying which are missing if not, before building anything;
2. checks the Gradle wrapper jar against Gradle's published checksum, installs a fixed JDK
   (Temurin 21.0.13), platform `android-37.0` and build-tools 37.0.0;
3. runs `testDebugUnitTest lintRelease` without the key in its environment;
4. decodes the keystore into the runner's temp folder, builds `assembleRelease` with it, then deletes it;
5. runs [tools/verify-apk.sh](tools/verify-apk.sh): apksigner must verify the APK, and its one signing
   certificate must be the one docs/install.md publishes, or the run fails;
6. creates a **draft** release `v1.2.3` with `burble-1.2.3.apk` and `burble-1.2.3.apk.sha256`.
   Its notes are GitHub's generated notes, then the APK's SHA-256 and certificate. If a draft for
   the tag already exists, for example one you wrote notes in, it keeps your notes and the files
   are replaced. A release that is already published is never touched: the run fails instead.

Nothing is published automatically. You read the draft, then press **Publish**.

The secrets are only readable by the build job. Only the release job, which runs none of the
repository's code, can write to the repository. Actions are pinned to commit SHAs.

### Turn it on (Tom, once)

The workflow reads the four repository secrets set on 2026-10-24: `BURBLE_KEYSTORE` (the keystore,
base64), `BURBLE_KEYSTORE_PASSWORD`, `BURBLE_KEY_ALIAS` (`burble`) and `BURBLE_KEY_PASSWORD` (the
same password). They are already in place, so the workflow only waits for GitHub Actions to run on
the repository: on the private repository Actions is blocked by a billing error, and public
repositories get Actions for free, so it starts working when the repository goes public.
`gh secret list` shows the names, never the values. If the key ever has to be uploaded again:

```bash
base64 -i burble-release.jks | gh secret set BURBLE_KEYSTORE
gh secret set BURBLE_KEYSTORE_PASSWORD    # paste the password at the prompt
gh secret set BURBLE_KEY_PASSWORD         # the same password
printf burble | gh secret set BURBLE_KEY_ALIAS
rm -P burble-release.jks                  # remove the exported copy
```

Then do a dry run: **Actions → Release → Run workflow** (or `gh workflow run release.yml -f
version=0.0.1`). It builds, signs and checks the certificate, writes the APK's SHA-256 and
certificate in the run's summary, and creates no release. Without the secrets the same run builds
an unsigned `burble-0.0.1-unsigned.apk` and says signing was skipped.

### Test build before a release

A dry run keeps its APK 3 days as the run's `release` artifact, signed with the release key, so it
installs over the released app. Give it the next version, so the phone takes it as an update:

```bash
gh workflow run release.yml -f version=1.2.4
gh run download "$(gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')" -n release
```

### Cut a release

```bash
git switch main && git pull
git tag v1.2.3 && git push origin v1.2.3
gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Then open the draft (the run's summary links it), check the notes, and publish it. To write the
notes first, create the draft before pushing the tag: `gh release create v1.2.3 --draft --title
"Burble 1.2.3" --notes-file notes.md`. A failed run can be re-run from the Actions page once the
cause is fixed. It replaces the draft's files.

## Locally (fallback)

Use this when Actions is down or the secrets aren't set. Export the keystore from Vaultwarden to a
file outside the repository, then build, test, lint and sign version 1.2.3 in one command. The
build reads the key only from these variables:

```bash
read -rs PW   # the release key's password, from Vaultwarden
BURBLE_KEYSTORE=/path/to/burble-release.jks BURBLE_KEY_ALIAS=burble \
  BURBLE_KEYSTORE_PASSWORD="$PW" BURBLE_KEY_PASSWORD="$PW" \
  ./gradlew --no-daemon -PversionName=1.2.3 testDebugUnitTest lintRelease assembleRelease
unset PW
```

Without the key, `assembleRelease` refuses rather than sign with the debug key.
`-PunsignedRelease` builds `app-release-unsigned.apk` instead, which nothing installs.

Name it, check the certificate, and publish it like CI does:

```bash
cp app/build/outputs/apk/release/app-release.apk burble-1.2.3.apk
tools/verify-apk.sh burble-1.2.3.apk             # fails unless signed with docs/install.md's certificate
shasum -a 256 burble-1.2.3.apk > burble-1.2.3.apk.sha256
git tag v1.2.3 && git push origin v1.2.3         # once CI is on, this tag push starts release.yml too
gh release create v1.2.3 burble-1.2.3.apk burble-1.2.3.apk.sha256 --draft --title "Burble 1.2.3" --generate-notes
```

Once Actions runs on the repository, the tag push also starts release.yml. It finds your draft and
replaces its files with its own build of the same commit, signed with the same key.
