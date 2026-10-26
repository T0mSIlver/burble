#!/usr/bin/env bash
# Times dictations of the bundled test clip through the real client, on an emulator with the debug
# build: the first 4.3 s of assets/test12s.opus play as the microphone, a stop follows 300 ms
# later, and the Latency log shows each step from the tap. Each run is one request to Google's
# server, so runs go 15 s apart.
#
#   tools/emulator/latency.sh [runs]
#
# Needs: a booted emulator with no lock screen, the debug APK built and ffmpeg.
set -e
runs=${1:-3}
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-latency}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT
ffmpeg -v error -y -i "$HERE/../../app/src/main/assets/test12s.opus" -ac 1 -ar 16000 -f s16le $OUT/clip.pcm
cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><boolean name="hideKeyboard" value="true" /><boolean name="languagesFromKeyboard" value="false" /><string name="whenIdle">FULL</string></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB push $OUT/clip.pcm /data/local/tmp/clip.pcm >/dev/null
$ADB shell "run-as dev.silver.burble sh -c 'mkdir -p shared_prefs files && cp /data/local/tmp/settings.xml shared_prefs/settings.xml && cp /data/local/tmp/clip.pcm files/clip.pcm'"
$ADB shell am start -W -n dev.silver.burble/.DebugFieldsActivity >/dev/null; sleep 2
$ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
sleep 4
$ADB shell input tap 540 400; sleep 1
for i in $(seq 1 $runs); do
  $ADB logcat -c
  $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver --es do clip --ei ms 4300 --ei stop 300 >/dev/null
  sleep 12
  echo "run $i"
  $ADB logcat -d -s Latency:D | grep 'Latency' | sed 's/^.*Latency *: */  /'
  if [ $i -lt $runs ]; then sleep 15; fi
done
