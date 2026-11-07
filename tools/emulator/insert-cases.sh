#!/usr/bin/env bash
# Checks how a dictation's words go into a field, on an emulator with the debug build: typed
# through the field's input connection, as a keyboard types (the default), or with the field's
# text rewritten (`rewrite`, the fallback, for comparison). Each case sets a field on
# DebugFieldsActivity up, dictates "Hello" and compares what the field holds. Rewriting fails
# `selection` (it appends instead) and `compose` (a field that lost focus gets the words at its end).
#   empty        an empty EditText gets the words alone
#   after        "Hi" gets "Hi Hello"
#   middle       the cursor after "Hello" in "Hello world": "Hello Hello world"
#   selection    "world" selected in "Hello world": replaced, "Hello Hello"
#   drawn-hint   a field that reports its placeholder as text, as WhatsApp's does: the words alone
#   compose      a Compose field that drops focus when the keyboard hides, cursor in the middle:
#                focused again, the words go at the cursor
#   rebuilt      a Compose field rebuilt when the keyboard hides, cursor in the middle: it lost the
#                cursor, so the words go at its end
#
#   tools/emulator/insert-cases.sh [type|rewrite] [case…]
#
# Needs: a booted emulator with no lock screen and the debug APK built. Prints PASS or FAIL per
# case, and exits 1 if any failed.
set -e
how=${1:-type}; shift || true
cases=${*:-empty after middle selection drawn-hint compose rebuilt}
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-insert}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT
send() { $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver "$@" >/dev/null; }
# The EditText nodes on screen as "text|desc|l t r b", top to bottom. uiautomator silences other
# accessibility services while it runs, so it only reads before and after a dictation.
fields() {
  $ADB shell uiautomator dump /sdcard/insert.xml >/dev/null
  $ADB shell cat /sdcard/insert.xml | grep -o '<node[^>]*class="android.widget.EditText"[^>]*>' |
    sed 's/.* text="\([^"]*\)".*content-desc="\([^"]*\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1|\2|\3 \4 \5 \6/'
}
# The field whose description is $1, or the $1-th field (from 1) when it's a number.
field() { if [[ $1 =~ ^[0-9]+$ ]]; then fields | sed -n "${1}p"; else fields | grep "|$1|"; fi; }
centre() { read l t r b <<<"${1##*|}"; echo $(( (l+r)/2 )) $(( (t+b)/2 )); }
frame() { $ADB shell dumpsys window windows | grep -A30 "u0 Burble}" | grep -m1 -o "frame=\[-\?[0-9]*,[0-9]*\]\[-\?[0-9]*,[0-9]*\]" | tr -c '0-9-' ' '; }

cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><boolean name="hideKeyboard" value="true" /><string name="whenIdle">FULL</string></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble mkdir -p shared_prefs && run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
$ADB shell settings put system accelerometer_rotation 0; $ADB shell settings put system user_rotation 0
$ADB shell am start -W -n dev.silver.burble/.DebugFieldsActivity >/dev/null
$ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
sleep 4
[ "$how" = rewrite ] && send --es insert rewrite || send --es insert type

# Scrolls the page to its top, or with "bottom" to its end. Swipes stay above the keyboard,
# which would take them for glide typing.
scroll() {
  local from=300 to=1150; [ "$1" = bottom ] && { from=1100; to=150; }
  for i in 1 2 3 4 5 6; do $ADB shell input swipe 540 $from 540 $to 120; done; sleep 1
}
# Taps field $1, empties it, types $2 (spaces as %s), then sends the key events in $3.
prepare() {
  local f; f=$(field "$1") || { echo "no field $1"; return 1; }
  $ADB shell input tap $(centre "$f"); sleep 1
  $ADB shell input keyevent KEYCODE_MOVE_END $(printf 'KEYCODE_DEL %.0s' $(seq 30))
  [ -n "$2" ] && $ADB shell input text "$2"
  for k in $3; do $ADB shell input keyevent $k; done # one at a time: Compose drops a burst
  sleep 1
}
dictate() {
  $ADB logcat -c
  read l t r b <<<$(frame)
  $ADB shell input tap $(( (l+r)/2 )) $(( (t+b)/2 )); sleep 1
  send --es do fake; sleep 0.5
  send --es text Hello --ei after 300; sleep 3
}
check() { # case, field, expected
  local got; got=$(field "$2" | cut -d'|' -f1)
  local how_went; how_went=$($ADB logcat -d -s Dictation:D Insert:D | grep -o "insert: [a-z' ]*\|rewrite [A-Za-z.]*\|delivered: [a-z]*\|insert fell back.*" | tr '\n' ';')
  if [ "$got" = "$3" ]; then echo "PASS $1: \"$got\" ($how_went)"
  else echo "FAIL $1: \"$got\", expected \"$3\" ($how_went)"; failed=1; fi
}

LEFT6="KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT"
failed=0
for c in $cases; do
  $ADB shell am start -W -n dev.silver.burble/.DebugFieldsActivity >/dev/null
  scroll top
  case $c in
    empty) prepare first ""; $ADB shell input keyevent KEYCODE_BACK; dictate; check $c first "Hello" ;;
    after) prepare first "Hi"; $ADB shell input keyevent KEYCODE_BACK; dictate; check $c first "Hi Hello" ;;
    middle) prepare first "Hello%sworld" "$LEFT6"; $ADB shell input keyevent KEYCODE_BACK; dictate; check $c first "Hello Hello world" ;;
    selection)
      prepare first "Hello%sworld"
      for i in 1 2 3 4 5; do $ADB shell input keycombination KEYCODE_SHIFT_LEFT KEYCODE_DPAD_LEFT; done
      $ADB shell input keyevent KEYCODE_BACK; dictate; check $c first "Hello Hello" ;;
    drawn-hint) scroll bottom; prepare "drawn hint" ""; $ADB shell input keyevent KEYCODE_BACK; dictate; check $c "drawn hint" "Hello" ;;
    # The keyboard stays up: hiding it, Burble makes these fields drop focus or rebuild.
    compose) prepare 2 "Hello%sworld" "$LEFT6"; dictate; check $c 2 "Hello Hello world" ;;
    rebuilt) prepare 5 "Hello%sworld" "$LEFT6"; dictate; check $c 5 "Hello world Hello" ;;
    *) echo "unknown case $c"; exit 2 ;;
  esac
  # Closes the keyboard Burble brought back, so the next case starts the same way; Back with no
  # keyboard would leave the page.
  $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent KEYCODE_BACK; sleep 1
done
send --es insert type
exit $failed
