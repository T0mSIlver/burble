#!/usr/bin/env bash
# Checks where a dictation's words go, on an emulator with the debug build:
#   tap     start in the first field, tap a Compose field while dictating: the words land in the
#           tapped field, none in the first.
#   switch  start in the first field, switch to Settings, stop: the words go to the clipboard;
#           back in the first app, nothing lands in the field.
#   autofocus-edit, autofocus-compose
#           open a screen whose field takes focus on its own (an EditText in onCreate, or a
#           Compose field through a FocusRequester, as chat apps do), keyboard down, never
#           tapped; tap the bubble, stop: the words land in that field.
#   autofocus-ignores
#           the same with an EditText that takes the set-text action and keeps its text: read
#           back, the words aren't there, so they go to the clipboard.
#   autofocus-late
#           start on the home screen, then open the Compose screen: its field took focus after
#           the start and wasn't tapped, so the words go to the clipboard; a second dictation
#           there lands in the field.
#
#   tools/emulator/where-text-goes.sh tap|switch|autofocus-edit|autofocus-compose|autofocus-ignores|autofocus-late
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
$ADB shell "run-as dev.silver.burble mkdir -p shared_prefs && run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
frame() { $ADB shell dumpsys window windows | grep -A30 "u0 Burble}" | grep -m1 -o "frame=\[-\?[0-9]*,[0-9]*\]\[-\?[0-9]*,[0-9]*\]" | tr -c '0-9-' ' '; }
# A dictation as the user starts it: a tap on the bubble, kept listening, then the words.
# A tap that starts no dictation is retried once, then reported as such, not as a wrong field.
tapBubble() {
  for try in 1 2; do
    read l t r b <<<$(frame)
    $ADB shell input tap $(( (l+r)/2 )) $(( (t+b)/2 )); sleep 0.8
    $ADB logcat -d -s Dictation:D | grep -q "started;" && return 0
    sleep 1
  done
  echo "the bubble tap didn't start a dictation"; $ADB shell dumpsys window windows | grep -E "u0 Burble( touch)?}" -A12 | grep -E "Window #|frame=|mAttrs|isVisible|mHasSurface" | head -12 > $OUT/missed-tap.txt; exit 2
}
dictate() {
  tapBubble
  send --es do fake; sleep 1
  send --es text $1 --ei after 300; sleep 2.5
}
went() { $ADB logcat -d -s Dictation:D Insert:D | grep -o "delivered: [a-z]*\|insert fell back.*" | tr '\n' ';'; }
if [[ "$mode" == autofocus-* ]]; then
  kind=${mode#autofocus-}; [ "$kind" = late ] && kind=compose
  $ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
  for i in $(seq 10); do sleep 1; [ -n "$(frame)" ] && break; done
  $ADB shell input keyevent KEYCODE_HOME; sleep 1
  words=Auto$RANDOM; late=""
  $ADB logcat -c
  if [ "$mode" = autofocus-late ]; then
    tapBubble
    send --es do fake; sleep 0.5
    $ADB shell am start -W -n dev.silver.burble/.DebugAutoFocusActivity --es kind $kind >/dev/null; sleep 2.5
    send --es text Late$RANDOM --ei after 300; sleep 2.5
    late=$(went); $ADB logcat -c
  else
    $ADB shell am start -W -n dev.silver.burble/.DebugAutoFocusActivity --es kind $kind >/dev/null; sleep 2.5
  fi
  dictate $words
  now=$(went)
  $ADB shell settings put secure enabled_accessibility_services null; sleep 1
  $ADB shell uiautomator dump /sdcard/where.xml >/dev/null
  text=$($ADB shell cat /sdcard/where.xml | grep -o '<node[^>]*class="android.widget.EditText"[^>]*>' | grep -o ' text="[^"]*"' | head -1)
  echo "log: ${late:+late: $late }$now"; echo "field:$text"
  ok=0
  if [ "$mode" = autofocus-ignores ]; then
    [[ "$now" == *"doesn't show it"* && "$now" == *"delivered: clipboard"* && "$text" != *$words* ]] && ok=1
  else
    [[ "$now" == *"delivered: inserted"* && "$text" == *$words* ]] && ok=1
  fi
  [ "$mode" = autofocus-late ] && [[ "$late" != *"delivered: clipboard"* ]] && ok=0
  [ $ok = 1 ] && echo PASS || { echo FAIL; exit 1; }
  exit 0
fi
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
