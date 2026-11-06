#!/usr/bin/env bash
# "With keyboard", in Messages: the bubble shows with the keyboard, goes after Back (the field
# keeps focus), stays through a dictation that hides the keyboard and as it comes back, and goes
# on the home screen. Prints each step, how long the bubble took to go after Back, then PASS or FAIL.
#
#   tools/emulator/with-keyboard.sh
#
# Needs: a booted, unlocked emulator with Google Messages and the debug APK built.
set -e
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-with-keyboard}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT
send() { $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver "$@" >/dev/null; }
# shown / hidden, from the touch window's NOT_TOUCHABLE flag
state() { $ADB shell dumpsys window windows | grep -A8 "u0 Burble touch}" | grep -m1 -q "NOT_TOUCHABLE" && echo hidden || echo shown; }
kb() { $ADB shell dumpsys input_method | grep -m1 -o "mInputShown=[a-z]*"; }
frame() { $ADB shell dumpsys window windows | grep -A30 "u0 Burble}" | grep -m1 -o "frame=\[-\?[0-9]*,[0-9]*\]\[-\?[0-9]*,[0-9]*\]" | tr -c '0-9-' ' '; }
fail=0; expect() { s=$(state); echo "$1: $s (want $2) $(kb)"; [ "$s" = "$2" ] || fail=1; }
cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><boolean name="hideKeyboard" value="true" /><string name="whenIdle">FULL</string><string name="bubbleShow">TYPING</string></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell monkey -p dev.silver.burble -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml" || true
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble mkdir -p shared_prefs && run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
$ADB shell input keyevent KEYCODE_HOME; sleep 1
$ADB shell am start -W -a android.intent.action.SENDTO -d sms:5554 >/dev/null; sleep 4; $ADB shell input keyevent KEYCODE_ESCAPE; sleep 1
$ADB shell uiautomator dump /sdcard/wk.xml >/dev/null
first=$($ADB shell cat /sdcard/wk.xml | grep -o '<node[^>]*class="android.widget.EditText"[^>]*>' | head -1 | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')
read l t r b <<<"$first"; fx=$(( (l+r)/2 )); fy=$(( (t+b)/2 ))
$ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
for i in $(seq 15); do sleep 1; [ -n "$(frame)" ] && break; done
sleep 1
$ADB shell input tap $fx $fy; sleep 2
expect "field tapped, keyboard up" shown
$ADB shell input keyevent KEYCODE_BACK
t0=$(date +%s%N); gone=""
while [ $(( ($(date +%s%N)-t0)/1000000 )) -lt 3000 ]; do [ "$(state)" = hidden ] && { gone=$(( ($(date +%s%N)-t0)/1000000 )); break; }; done
echo "Back: hidden after ${gone:-never} ms"; [ -n "$gone" ] || fail=1
sleep 1; expect "keyboard closed by Back" hidden
$ADB shell input tap $fx $fy; sleep 2
expect "field tapped again" shown
# A dictation hides the keyboard; the bubble must stay through it and after the words land.
$ADB logcat -c
read l t r b <<<$(frame); $ADB shell input tap $(( (l+r)/2 )) $(( (t+b)/2 )); sleep 1
send --es do fake; sleep 1
expect "dictating, keyboard hidden by Burble" shown
send --es text Kb$RANDOM --ei after 300
seen=""; for i in $(seq 25); do seen="$seen $(state)"; done
echo "after the words, polled:$seen"; [[ "$seen" == *hidden* ]] && { echo "bubble blinked out after the dictation"; fail=1; }
expect "words landed, keyboard back" shown
$ADB logcat -d -s Dictation:D Insert:D | grep -o "delivered: [a-z]*" | head -1
$ADB shell input keyevent KEYCODE_HOME; sleep 2
expect "home screen" hidden
$ADB shell settings put secure enabled_accessibility_services null
[ $fail = 0 ] && echo PASS || { echo FAIL; exit 1; }
