#!/usr/bin/env bash
# Runs the device-lab tests (app/src/androidTest/.../lab) on Firebase Test Lab's real phones:
# two Samsungs, an OPPO, a OnePlus and a Motorola, with a video and logcat of each, then copies
# the results here. docs/testlab.md says what it needs (a Firebase project, gcloud) and costs.
#
#   tools/testlab.sh                 # the matrix below; one short request to Google's server per phone
#   SERVER=off tools/testlab.sh      # no request to Google's server: every text is the stand-in's
#   DEVICES="e1q:36 a16x:36" tools/testlab.sh
#   TESTS=dev.silver.burble.lab.OverlayTest tools/testlab.sh   # one class (or class#method)
#
# Needs: ANDROID_HOME, Java 21, gcloud logged in with a project set (gcloud config set project …).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/.." && pwd)
# model:API. Test Lab's catalogue changes: `gcloud firebase test android models list` has the
# current one, and this script checks each before it runs. Test Lab has no Xiaomi or Redmi on
# Android 12+ (Burble's minimum), so ColorOS (OPPO), as eager to stop background apps, stands in.
DEVICES=${DEVICES:-"e1q:36 a16x:36 OP573DL1:34 CPH2449:34 cuscoi:35"}
#   e1q       Samsung Galaxy S24, One UI 8 (Android 16): the flagship
#   a16x      Samsung Galaxy A16 5G, One UI 8: the budget A series, little memory
#   OP573DL1  OPPO CPH2557, ColorOS (Android 14): stands in for Xiaomi's HyperOS
#   CPH2449   OnePlus 11 5G, OxygenOS (Android 14)
#   cuscoi    Motorola edge 50 fusion (Android 15)
SERVER=${SERVER:-on}
OUT=${OUT:-$ROOT/build/testlab}

command -v gcloud >/dev/null || { echo "gcloud is missing: docs/testlab.md, step 2"; exit 1; }
project=$(gcloud config get-value project 2>/dev/null)
[ -n "$project" ] || { echo "no gcloud project: gcloud config set project <id> (docs/testlab.md)"; exit 1; }

args=()
for d in $DEVICES; do
  model=${d%%:*}; version=${d##*:}
  versions=$(gcloud firebase test android models describe "$model" --format='value(supportedVersionIds)' 2>/dev/null || true)
  if [ -z "$versions" ]; then echo "skipped: $model isn't in Test Lab's catalogue any more"; continue; fi
  if ! tr ',;' '  ' <<<"$versions" | grep -qw "$version"; then echo "skipped: $model has no Android API $version (has $versions)"; continue; fi
  args+=(--device "model=$model,version=$version,locale=en_US,orientation=portrait")
done
[ ${#args[@]} -gt 0 ] || { echo "no device left to run on"; exit 1; }

(cd "$ROOT" && ./gradlew -q assembleDebug assembleDebugAndroidTest)
stamp=$(date +%Y%m%d-%H%M%S)
[ -n "${TESTS:-}" ] && args+=(--test-targets "class $TESTS")
mkdir -p "$OUT"
set +e
gcloud firebase test android run \
  --type instrumentation \
  --app "$ROOT/app/build/outputs/apk/debug/app-debug.apk" \
  --test "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  "${args[@]}" \
  --use-orchestrator \
  --environment-variables "server=$SERVER" \
  --directories-to-pull /sdcard/Android/data/dev.silver.burble/files/lab \
  --record-video \
  --timeout 15m \
  --num-flaky-test-attempts 0 \
  --results-history-name burble-lab \
  --results-dir "burble-lab/$stamp" \
  2>&1 | tee "$OUT/$stamp.log"
status=${PIPESTATUS[0]}
set -e

# Videos, logcats, screenshots and JUnit XML, per phone.
bucket=$(grep -o 'storage/browser/[^/]*' "$OUT/$stamp.log" | head -1 | cut -d/ -f3)
if [ -n "$bucket" ]; then
  gcloud storage cp -r "gs://$bucket/burble-lab/$stamp" "$OUT/" >/dev/null && echo "results: $OUT/$stamp"
fi
exit $status
