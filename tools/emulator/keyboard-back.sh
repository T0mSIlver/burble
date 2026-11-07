#!/usr/bin/env bash
# Checks that the keyboard comes back after a dictation, and how fast, on an emulator with the
# debug build and "Hide keyboard during dictation" on. Prints, per app and flow, whether the
# keyboard is up 3 s after the words land and the time from the words to the keyboard shown.
#
#   tools/emulator/keyboard-back.sh [app ...]    apps: edittext compose chrome-bar chrome-page messages
#   VERBOSE=1 also prints Burble's and Android's keyboard logs for each case.
#
# Flows:
#   field    tap the field, start, stop: the words land in it
#   tapped   start on the home screen, open the app, tap the field, stop: the words land in it
#   copied   tap the field in the app, start on the home screen, reopen the app, stop: no field
#            was tapped or started in, so the words go to the clipboard; the keyboard comes back
#            on the field that has focus
#
# Needs: a booted emulator with no lock screen, the debug APK built, Chrome and Messages, and
# python3 on the host (it serves Chrome's test page).
set -e
ADB=${ADB:-adb}; OUT=${OUT:-/tmp/burble-keyboard}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
apps=${*:-edittext compose chrome-bar chrome-page messages}
mkdir -p $OUT/web
send() { $ADB shell am broadcast -n dev.silver.burble/.DebugReceiver "$@" >/dev/null; }
shown() { $ADB shell dumpsys input_method | grep -o -m1 "mInputShown=[a-z]*" | cut -d= -f2; }

cat > $OUT/web/t.html <<H
<!doctype html><meta name="viewport" content="width=device-width"><body style="margin:0;padding:20px"><textarea style="width:90%;height:120px;font-size:20px"></textarea></body>
H
python3 -m http.server 8767 --directory $OUT/web >/dev/null 2>&1 & server=$!
trap 'kill $server' EXIT
$ADB reverse tcp:8767 tcp:8767 >/dev/null

# open APP: brings the app up on its test screen. FIELD: where its field is.
open() {
  case $1 in
    edittext|compose) $ADB shell am start -W -n dev.silver.burble/.DebugFieldsActivity >/dev/null ;;
    chrome-*) $ADB shell am start -W -a android.intent.action.VIEW -d http://localhost:8767/t.html com.android.chrome >/dev/null ;;
    messages) $ADB shell am start -W -a android.intent.action.SENDTO -d sms:5551234 com.google.android.apps.messaging >/dev/null ;;
  esac
  sleep 2.5
}
field() {
  case $1 in
    edittext) echo 400 219 ;;
    compose) echo 400 350 ;;
    chrome-bar) echo 450 205 ;;
    chrome-page) echo 500 495 ;;
    messages) echo 450 2240 ;;
  esac
}

cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><boolean name="hideKeyboard" value="true" /><string name="whenIdle">FULL</string></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell settings put secure enabled_accessibility_services null; $ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble mkdir -p shared_prefs && run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
# Android may leave a reinstalled service unbound: switch it off and on until it binds.
for i in 1 2 3 4 5; do
  $ADB shell settings put secure enabled_accessibility_services null; sleep 1
  $ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService; sleep 3
  $ADB shell dumpsys accessibility | grep -q "Bound services:{Service\[label=Burble" && break
done

for app in $apps; do
  for flow in field tapped copied; do
    $ADB shell input keyevent KEYCODE_HOME; sleep 1
    case $flow in
      field) open $app; $ADB shell input tap $(field $app); sleep 1.5; send --es do start; send --es do fake ;;
      tapped) send --es do start; send --es do fake; sleep 1; open $app; $ADB shell input tap $(field $app) ;;
      copied) open $app; $ADB shell input tap $(field $app); sleep 1.5; $ADB shell input keyevent KEYCODE_HOME; sleep 1
              send --es do start; send --es do fake; sleep 1; open $app ;;
    esac
    sleep 1.5
    hidden=$(shown)
    $ADB logcat -c
    send --es text Back$RANDOM --ei after 300
    sleep 3
    up=$(shown)
    ms=$($ADB logcat -d -v epoch | python3 -c '
import sys
t0=None; nofield=""
for l in sys.stdin:
    if "no field on screen" in l: nofield=", no focused field"
    if "delivered:" in l: t0=float(l.split()[0]); went=l.split("delivered: ")[1].strip()
    elif t0 and "ImeTracker" in l and "onShown" in l: print(f"{went} {int((float(l.split()[0])-t0)*1000)} ms"); break
else:
    print((went if t0 else "nothing delivered") + nofield)
')
    printf '%-12s %-7s hidden while dictating=%-5s keyboard up=%-5s %s\n' $app $flow "$([ "$hidden" = false ] && echo yes || echo no)" "$up" "$ms"
    [ -n "$VERBOSE" ] && $ADB logcat -d | grep -E "Insert|ImeTracker|delivered" | cut -c7-150 | sed 's/^/      /'
    $ADB shell input keyevent KEYCODE_BACK
  done
done
