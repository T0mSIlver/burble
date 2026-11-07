#!/usr/bin/env bash
# Records the bubble going from full to its idle look and back on touch, over a striped page,
# and prints per frame the bubble's box (its blue pixels, not the shadow), so jumps and stutters
# show as numbers. Also keeps the overlay's frame times from gfxinfo.
#
#   tools/emulator/dock-anim.sh SHRINK|DOT right|left [out-dir]
#   TRIGGER=scroll tools/emulator/dock-anim.sh ...   scrolls the page 1 s in, four times, rather
#                                                    than waiting the 2 s out
#
# Out: <out-dir>/dock.mp4, frames.txt (t left top right bottom), gfxinfo.txt.
# Needs: a booted emulator with no lock screen, the debug APK built, Chrome, ffmpeg, python3.
set -e
look=$1; side=$2; OUT=${3:-/tmp/burble-dock/$look-$side}
ADB=${ADB:-adb}; HERE=$(cd "$(dirname "$0")" && pwd)
APK=${APK:-$HERE/../../app/build/outputs/apk/debug/app-debug.apk}
mkdir -p $OUT/web
# A port of its own each run: an earlier run's server may still hold its port.
PORT=$(( 8770 + RANDOM % 200 ))
frame() { $ADB shell dumpsys window windows | grep -A30 "u0 Burble}" | grep -m1 -o "frame=\[-\?[0-9]*,[0-9]*\]\[-\?[0-9]*,[0-9]*\]" | tr -c '0-9-' ' '; }
echo '<!doctype html><meta name="viewport" content="width=device-width"><body style="margin:0;height:8000px;background:repeating-linear-gradient(#fff 0 120px,#eee 120px 124px)">' > $OUT/web/blank.html
python3 -m http.server $PORT --directory $OUT/web >/dev/null 2>&1 & server=$!
trap 'kill $server' EXIT
$ADB reverse tcp:$PORT tcp:$PORT >/dev/null

cat > $OUT/settings.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="onboarded" value="true" /><boolean name="bubbleOn" value="true" /><string name="whenIdle">$look</string><int name="bubbleSizeDp" value="64" /></map>
X
$ADB install -r -g "$APK" >/dev/null
$ADB shell am force-stop dev.silver.burble
$ADB shell "run-as dev.silver.burble rm -rf no_backup/pending shared_prefs/overlay.xml"
$ADB push $OUT/settings.xml /data/local/tmp/settings.xml >/dev/null
$ADB shell "run-as dev.silver.burble mkdir -p shared_prefs && run-as dev.silver.burble cp /data/local/tmp/settings.xml shared_prefs/settings.xml"
for i in 1 2 3 4 5; do
  $ADB shell settings put secure enabled_accessibility_services null; sleep 1
  $ADB shell settings put secure enabled_accessibility_services dev.silver.burble/dev.silver.burble.InsertionService; sleep 3
  [ -n "$(frame)" ] && break
done
$ADB shell am start -W -a android.intent.action.VIEW -d http://localhost:$PORT/blank.html com.android.chrome >/dev/null; sleep 3
W=$($ADB shell wm size | grep -o '[0-9]*x' | tr -d x)
read l t r b <<<$(frame); cy=$(( (t+b)/2 ))
# It may have docked already: a finger on the edge brings it back to full, at its window's centre.
$ADB shell input motionevent DOWN $(( W - 8 )) $cy; sleep 0.6; $ADB shell input motionevent CANCEL $(( W - 8 )) $cy
if [ "$side" = left ]; then
  $ADB shell input swipe $(( (l+r)/2 )) $cy 150 $cy 600; sleep 1
fi
# Full again, with the 2 s count starting now: a touch that isn't a tap.
x=$([ "$side" = left ] && echo 60 || echo $(( W - 60 )))
$ADB shell input motionevent DOWN $x $cy; sleep 0.3; $ADB shell input motionevent CANCEL $x $cy
$ADB shell dumpsys gfxinfo dev.silver.burble reset >/dev/null
$ADB shell "screenrecord --bit-rate 20000000 --time-limit 9 /sdcard/dock.mp4" & rec=$!; sleep 0.5
if [ "$TRIGGER" = scroll ]; then
  sleep 1
  # Away from the bubble, on the other side: the first finger down docks it, the rest mustn't
  # restart the motion.
  sx=$([ "$side" = left ] && echo 800 || echo 280)
  for i in 1 2 3 4; do $ADB shell input swipe $sx 1700 $sx 1100 200; done
  sleep 1
else
  sleep 4.5
fi
# Docked: a finger on the edge brings it back to full, its release starts the count again.
edge=$([ "$side" = left ] && echo 8 || echo $(( W - 8 )))
$ADB shell input motionevent DOWN $edge $cy; sleep 1.2; $ADB shell input motionevent CANCEL $edge $cy
wait $rec
$ADB shell dumpsys gfxinfo dev.silver.burble framestats > $OUT/gfxinfo.txt
$ADB pull /sdcard/dock.mp4 $OUT/dock.mp4 >/dev/null
ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 $OUT/dock.mp4 > $OUT/t.txt
# The band the bubble lives in: its window's rows, the screen's full width.
ffmpeg -loglevel error -y -i $OUT/dock.mp4 -map 0:v:0 -vsync 0 -vf "crop=$W:$((b-t)):0:$t,format=rgb24" -f rawvideo $OUT/band.raw
python3 - $OUT $W $((b-t)) $t <<'P'
import sys
out, W, H, top = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
t = [float(x) for x in open(out + '/t.txt').read().split()]
data = open(out + '/band.raw', 'rb').read(); fs = W * H * 3
with open(out + '/frames.txt', 'w') as f:
    for i in range(min(len(t), len(data) // fs)):
        o = i * fs; xs = []; ys = []
        for y in range(0, H, 2):
            row = o + y * W * 3
            for x in range(0, W, 2):
                p = row + x * 3
                if data[p + 2] - data[p] > 14: xs.append(x); ys.append(y)
        if xs: f.write(f"{t[i]:.4f} {min(xs)} {min(ys)+top} {max(xs)} {max(ys)+top}\n")
        else: f.write(f"{t[i]:.4f} - - - -\n")
P
grep -E "Total frames|Janky|percentile" $OUT/gfxinfo.txt | head -6
echo "frames: $(wc -l < $OUT/frames.txt) in $(tail -1 $OUT/t.txt) s -> $OUT"
