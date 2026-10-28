#!/usr/bin/env bash
# Checks that an APK is signed with Burble's release key: apksigner verifies it with APK Signature
# Scheme v2 or v3, and its one signing certificate is the one docs/install.md publishes. Prints that
# certificate's SHA-256 on success. release.yml runs it on every release, RELEASING.md by hand.
#
#   tools/verify-apk.sh burble-1.2.3.apk
#
# apksigner comes from $APKSIGNER, else the newest build-tools in $ANDROID_HOME.
set -euo pipefail

apk="${1:?usage: tools/verify-apk.sh <apk>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
apksigner="${APKSIGNER:-$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/* | sort -V | tail -1)/apksigner}"
fail() { echo "::error::$*" >&2; exit 1; }

# The fingerprint block in docs/install.md: 32 colon-separated hex bytes on a line of their own.
published="$(grep -E '^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$' "$root/docs/install.md" || true)"
[ -n "$published" ] && [ "$(wc -l <<<"$published")" -eq 1 ] ||
  fail "docs/install.md must publish exactly one SHA-256 certificate fingerprint"
expected="$(tr -d ':' <<<"$published" | tr 'A-F' 'a-f')"

out="$("$apksigner" verify --verbose --print-certs "$apk" 2>/dev/null)" || fail "apksigner rejects $apk (unsigned, or damaged)"
grep -Eq '^Verified using v(2|3|3\.1) scheme .*: true$' <<<"$out" || fail "$apk is not signed with APK Signature Scheme v2 or v3"

# "Signer #1 certificate SHA-256 digest: …"; a source stamp's certificate isn't a signer.
certs="$(grep -v 'Source Stamp' <<<"$out" | sed -nE 's/^.*[Ss]igner.*certificate SHA-256 digest: *([0-9A-Fa-f]{64}).*$/\1/p' |
  tr 'A-F' 'a-f' | sort -u)"
[ -n "$certs" ] || fail "apksigner shows no signing certificate for $apk"
[ "$(wc -l <<<"$certs")" -eq 1 ] || fail "$apk has more than one signing certificate"
[ "$certs" = "$expected" ] ||
  fail "$apk is signed with certificate $certs, not the one in docs/install.md ($expected). Installed copies would refuse it as an update."

sed -E 's/(..)/\1:/g; s/:$//' <<<"$certs" | tr 'a-f' 'A-F'
