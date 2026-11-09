#!/usr/bin/env bash
# Checks the bubble's flight out of a keyboard's mic key and back, on an emulator with the debug
# build, adb's shell standing in for a keyboard that hands off (HandoffDebug). Each case focuses a
# field on DebugFieldsActivity, says hello and starts from the key, fakes the words, and checks:
#   normal     the words land, the bubble flies back into the key as the keyboard comes back, a
#              hello after the handover holds nothing (holdKeyUntil 0), and the bubble ends at home.
#              The hello's own hold isn't checked: the emulator's keyboard coming back can start the
#              flight first, and a `content call` takes longer than the 460 ms flight.
#   clipboard  started, then Settings opened: the words go to the clipboard and the bubble goes
#              home at once, with no flight back
#   reduced    animations off: the words land, no hello holds the key, no flight back
#
#   tools/emulator/handoff-flight.sh [case…]
#
# Needs: a booted emulator with no lock screen and the debug APK built. Prints PASS or FAIL per
# case, and exits 1 if any failed.
cases=${*:-normal clipboard reduced}
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-handoff}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT
send() { $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver "$@" >/dev/null; }
call() { $ADB shell content call --uri content://dev.silver.burble.handoff --method "$@" | grep -o "{.*}"; }
hold() { call hello $KEY | grep -o "holdKeyUntil=[0-9]*" | cut -d= -f2; }
KEY="--extra keyLeft:f:880 --extra keyTop:f:2180 --extra keyRight:f:1000 --extra keyBottom:f:2300"
frame() { $ADB shell dumpsys window windows | grep -A30 "u0 Burble}" | grep -m1 -o "frame=\[-\?[0-9]*,[0-9]*\]\[-\?[0-9]*,[0-9]*\]"; }
logged() { $ADB logcat -d -s Overlay:D Dictation:D | grep -q "$1"; }

cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><boolean name="hideKeyboard" value="true" /><string name="whenIdle">FULL</string></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put global animator_duration_scale 1
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble mkdir -p shared_prefs && run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
$ADB shell settings put system accelerometer_rotation 0; $ADB shell settings put system user_rotation 0
$ADB shell am start -W -n dev.silver.burble/.DebugFieldsActivity >/dev/null
sleep 2
$ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService
for i in $(seq 60); do [ -n "$(frame)" ] && break; sleep 0.5; done # the service shows the bubble once bound
home=$(frame)
[ -n "$home" ] || { echo "FAIL: no bubble 30 s after turning the service on"; exit 2; }
$ADB shell uiautomator dump /sdcard/handoff.xml >/dev/null
read l t r b <<<$($ADB shell cat /sdcard/handoff.xml | grep -o '<node[^>]*class="android.widget.EditText"[^>]*>' | head -1 |
  sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')
fx=$(( (l+r)/2 )); fy=$(( (t+b)/2 ))

failed=0
check() { if [ "$2" = 1 ]; then echo "PASS $1"; else echo "FAIL $1: $3"; failed=1; fi; }
for c in $cases; do
  [ $c = reduced ] && $ADB shell settings put global animator_duration_scale 0
  # A fresh screen each time, without stopping the app: that would unbind the service.
  $ADB shell am start -W -f 0x10008000 -n dev.silver.burble/.DebugFieldsActivity >/dev/null
  for i in $(seq 20); do [ -n "$(frame)" ] && break; sleep 0.5; done; sleep 1
  $ADB shell input tap $fx $fy; sleep 1.5
  $ADB logcat -c
  call hello $KEY >/dev/null; sleep 1
  started=$(call start $KEY --extra keyboardTop:f:1500 | grep -c "accepted=true")
  sleep 1.5; during=$(frame)
  h=""
  [ $c = clipboard ] && { $ADB shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 1; }
  send --es do fake; sleep 0.3; send --es text Hello --ei after 300; sleep 0.9
  case $c in
    normal|reduced)
      sleep 1.5; h=$(hold)
      $ADB shell input keyevent KEYCODE_BACK; sleep 4 ;;
    clipboard) sleep 3 ;;
  esac
  after=$(frame)
  why="started $started, during $during, after $after, hold after ${h:-}, log: $($ADB logcat -d -s Overlay:D Dictation:D | grep -o "delivered: [a-z]*\|handoff: [a-z ,]*" | tr '\n' ';')"
  ok=0
  case $c in
    normal) [ "$started" = 1 ] && [ "$during" != "$home" ] && [ "$h" = 0 ] &&
              logged "delivered: inserted" && logged "back into the key" && [ "$after" = "$home" ] && ok=1 ;;
    clipboard) [ "$started" = 1 ] && logged "delivered: clipboard" && logged "no keyboard to go back into, home" &&
              [ "$after" = "$home" ] && ok=1 ;;
    reduced) [ "$started" = 1 ] && [ "$h" = 0 ] && logged "delivered: inserted" && ! logged "back into the key" &&
              [ "$after" = "$home" ] && ok=1 ;;
  esac
  check $c $ok "$why"
  [ $c = reduced ] && $ADB shell settings put global animator_duration_scale 1
  $ADB shell input keyevent KEYCODE_HOME
done
exit $failed
