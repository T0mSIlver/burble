#!/usr/bin/env bash
# Checks where a dictation's words go, on an emulator with the debug build:
#   tap     start in the first field, tap a Compose field while dictating: the words land in the
#           tapped field, none in the first.
#   switch  start in the first field, switch to Settings, stop: the words go to the clipboard;
#           back in the first app, nothing lands in the field.
#
#   tools/emulator/where-text-goes.sh tap|switch
#
# Needs: a booted emulator with no lock screen and the debug APK built. Prints PASS or FAIL.
set -e
mode=$1
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-where}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT
send() { $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver "$@" >/dev/null; }
# The debug fields' text, top to bottom, one per line. uiautomator silences other
# accessibility services while it runs, so it only reads before and after a dictation.
fields() { $ADB shell uiautomator dump /sdcard/where.xml >/dev/null; $ADB shell cat /sdcard/where.xml | grep -o '<node[^>]*class="android.widget.EditText"[^>]*>' | sed 's/.* text="\([^"]*\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1|\2 \3 \4 \5/'; }
centre() { read l t r b <<<"${1#*|}"; echo $(( (l+r)/2 )) $(( (t+b)/2 )); }

cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><boolean name="hideKeyboard" value="true" /><string name="whenIdle">FULL</string></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
$ADB shell am start -W -n dev.silver.burble/.DebugFieldsActivity >/dev/null; sleep 2
mapfile -t before < <(fields)
first=$(centre "${before[0]}"); compose=$(centre "${before[1]}")
$ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
sleep 4
$ADB logcat -c
$ADB shell input tap $first; sleep 1
send --es do start; send --es do fake; sleep 1
words=Where$RANDOM
if [ "$mode" = tap ]; then
  $ADB shell input tap $compose; sleep 1
  send --es text $words --ei after 300; sleep 2
else
  $ADB shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 2
  send --es text $words --ei after 300; sleep 2
  $ADB shell input keyevent KEYCODE_BACK; sleep 3
fi
went=$($ADB logcat -d -s Dictation:D Insert:D | grep -o "delivered: [a-z]*\|insert fell back.*" | tr '\n' ';')
$ADB shell settings put secure enabled_accessibility_services null
mapfile -t after < <(fields)
echo "log: $went"
printf 'fields: %s\n' "${after[@]%%|*}"
ok=0
if [ "$mode" = tap ]; then
  [[ "${after[1]%%|*}" == *$words* && "${after[0]%%|*}" != *$words* && "$went" == *"delivered: inserted"* ]] && ok=1
else
  [[ "$went" == *"delivered: clipboard"* ]] && ! printf '%s\n' "${after[@]}" | grep -q $words && ok=1
fi
[ $ok = 1 ] && echo PASS || { echo FAIL; exit 1; }
