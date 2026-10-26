#!/usr/bin/env bash
# Records the debug build on an emulator and prints the bubble's edges on its centre row, frame
# by frame, while (pill) a "didn't hear anything" label comes and goes, or (resize) a docked dot
# goes through three sizes. A bubble that holds still prints one outer edge throughout.
#
#   tools/emulator/bubble-stays.sh pill right|left
#   tools/emulator/bubble-stays.sh resize
#
# Needs: a booted emulator with no lock screen, the debug APK built, ffmpeg, python3.
set -e
mode=$1; side=${2:-right}
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-stays}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT
frame() { $ADB shell dumpsys window windows | grep -A30 "u0 Burble}" | grep -m1 -o "frame=\[-\?[0-9]*,[0-9]*\]\[-\?[0-9]*,[0-9]*\]" | tr -c '0-9-' ' '; }
look=FULL; [ "$mode" = resize ] && look=DOT
cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><string name="whenIdle">$look</string><int name="bubbleSizeDp" value="64" /></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
$ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
for i in $(seq 30); do sleep 1; [ -n "$(frame)" ] && break; done
[ -n "$(frame)" ] || { echo "no bubble window: is the accessibility service on?"; exit 1; }
$ADB shell input keyevent KEYCODE_HOME; sleep 2
if [ "$side" = left ]; then read l t r b <<<$(frame); $ADB shell input swipe $(( (l+r)/2 )) $(( (t+b)/2 )) 150 $(( (t+b)/2 )) 600; sleep 2; fi
[ "$mode" = resize ] && sleep 4
read l t r b <<<$(frame); cy=$(( (t+b)/2 ))
$ADB shell "screenrecord --time-limit 8 /sdcard/stays.mp4" & sleep 1.5
if [ "$mode" = pill ]; then
  $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver --es error NOTHING_HEARD >/dev/null
else
  for s in 40 96 64; do $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver --ei size $s >/dev/null; sleep 1.5; done
fi
wait; sleep 1
$ADB pull /sdcard/stays.mp4 $OUT/stays.mp4 >/dev/null
ffmpeg -loglevel error -y -i $OUT/stays.mp4 -map 0:v:0 -vsync 0 -vf "crop=1080:2:0:$cy,format=rgb24" -f rawvideo $OUT/rows.raw
python3 $HERE/edges.py $OUT/rows.raw $side | awk '{print $2}' | sort | uniq -c
