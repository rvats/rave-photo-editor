#!/usr/bin/env bash
# Smoke test and store screenshots on an emulator.
# Installs the debug build, opens a sample photo through the app's share target, runs each tool,
# saves a screenshot after each one, and checks the app is still running with no crash in logcat.
# Usage: screenshots.sh <output dir>
set -uo pipefail

OUT="${1:?usage: screenshots.sh <output dir>}"
mkdir -p "$OUT"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.ravemanagementllc.ravephotoeditor
APK=app/build/outputs/apk/debug/app-debug.apk
LOG="$OUT/smoke.log"
UI="$OUT/ui.xml"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$UI" >/dev/null 2>&1; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }
shot() { sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; log "screenshot $1"; }

tap() {
  local xy
  dump
  if ! xy="$(python3 "$HERE/find_node.py" "$UI" "$1")"; then
    log "could not find '$1' on screen"
    return 1
  fi
  adb shell input tap $xy
  log "tapped '$1' at $xy"
  sleep 1
}

wait_idle() {
  local i
  for i in $(seq 1 120); do
    dump
    if ! grep -qE 'Opening photo|Finding the subject|Saving|Preparing to share' "$UI"; then
      return 0
    fi
    sleep 1
  done
  log "still busy after 120 s"
  return 1
}

# The text of an error snackbar on screen, if any.
snack() {
  grep -oE 'text="[^"]*(downloading|work on this photo|No subject|too large|match the photo|Couldn)[^"]*"' "$UI" | head -1
}

adb wait-for-device
{
  echo "Android $(adb shell getprop ro.build.version.release)"
  adb shell wm size
  adb shell wm density
  adb shell dumpsys package com.google.android.gms | grep -m1 versionName
} >> "$LOG" 2>&1

log "installing $APK"
adb install -r "$APK" >> "$LOG" 2>&1 || { log "install failed"; exit 1; }

# A clean status bar for the store screenshots.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null 2>&1; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false

# 1. First launch, no photo yet.
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" >> "$LOG" 2>&1
sleep 4
alive || log "APP NOT RUNNING after launch"
shot 00-start

# 2. Open a sample photo through the app's own share target (served by its FileProvider).
python3 "$HERE/sample_photo.py" "$OUT/sample.jpg" "$OUT/sample-photo-credit.txt" >> "$LOG" 2>&1 \
  || { log "no sample photo, stopping"; adb logcat -d -v time > "$OUT/logcat.txt"; exit 1; }
adb push "$OUT/sample.jpg" /data/local/tmp/sample.jpg >/dev/null
adb shell "run-as $PKG mkdir -p cache/shared"
adb shell "cat /data/local/tmp/sample.jpg | run-as $PKG sh -c 'cat > cache/shared/sample.jpg'"
adb shell am start -W -n "$PKG/.MainActivity" -a android.intent.action.SEND -t image/jpeg \
  --eu android.intent.extra.STREAM "content://$PKG.fileprovider/shared/sample.jpg" >> "$LOG" 2>&1
sleep 3
wait_idle
alive || log "APP NOT RUNNING after opening the photo"
shot 01-photo

# 3. AI Cutout. The first use may wait for Play services to download the ML Kit model, so retry.
cut_ok=0
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  tap "Remove background" || break
  msg=""
  for j in $(seq 1 30); do
    dump
    if grep -qE 'text="Remove background"[^>]*enabled="false"' "$UI" \
       && ! grep -q 'Finding the subject' "$UI"; then
      cut_ok=1
      break
    fi
    msg="$(snack)"
    [ -n "$msg" ] && break
    sleep 1
  done
  [ "$cut_ok" = 1 ] && break
  log "cutout attempt $attempt: ${msg:-no result}"
  sleep 20
done
if [ "$cut_ok" = 1 ]; then
  shot 02-cutout
  # 4. Portrait blur, then 5. a new backdrop colour.
  tap "Portrait" && tap "Strong" && sleep 1 && wait_idle && shot 03-portrait
  tap "Backdrop" && tap "Sky background" && sleep 1 && wait_idle && shot 04-backdrop
  tap "Off" && wait_idle
else
  log "AI cutout did not work on this emulator"
fi

# 6. Auto enhance, 7. a filter, 8. the adjust sliders.
tap "Enhance" && tap "Auto enhance" && sleep 1 && wait_idle && shot 05-enhance
tap "Filters" && sleep 1 && tap "Vivid" && sleep 1 && wait_idle && shot 06-filters
tap "Adjust" && sleep 1 && shot 07-adjust

# 9. Save at full size to the gallery.
tap "Save" && sleep 2 && wait_idle
log "after save: $(grep -oE 'text="Saved[^"]*"' "$UI" | head -1)"
adb shell ls -l "/sdcard/Pictures/RAVE\ Photo\ Editor/" >> "$LOG" 2>&1

alive && log "app still running" || log "APP NOT RUNNING at the end"

# 10. The release build (shrunk by R8, signed with a throwaway test key): open the photo through
#     MediaStore like a gallery app would, then run the AI cutout, enhance, a filter and save.
REL="${RELEASE_TEST_APK:-}"
if [ -n "$REL" ] && [ -f "$REL" ]; then
  log "release build test: $REL"
  adb uninstall "$PKG" >> "$LOG" 2>&1
  adb install "$REL" >> "$LOG" 2>&1 || log "release install failed"
  adb push "$OUT/sample.jpg" /sdcard/Pictures/rave-sample.jpg >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/rave-sample.jpg >/dev/null 2>&1
  adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
  id=""
  for i in $(seq 1 15); do
    id="$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name \
      | grep 'rave-sample.jpg' | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)"
    [ -n "$id" ] && break
    sleep 2
  done
  if [ -n "$id" ]; then
    adb shell am start -W -n "$PKG/.MainActivity" -a android.intent.action.SEND -t image/jpeg \
      --eu android.intent.extra.STREAM "content://media/external/images/media/$id" --grant-read-uri-permission >> "$LOG" 2>&1
    sleep 4
    wait_idle
    if grep -q 'text="Remove background"' "$UI"; then log "release: photo opened"; else log "release: photo did NOT open"; fi
    shot 10-release-photo
    if tap "Remove background"; then
      for j in $(seq 1 40); do
        dump
        if grep -qE 'text="Remove background"[^>]*enabled="false"' "$UI" && ! grep -q 'Finding the subject' "$UI"; then
          log "release: AI cutout worked"; shot 11-release-cutout; break
        fi
        m="$(snack)"; [ -n "$m" ] && { log "release: cutout message $m"; break; }
        sleep 1
      done
    fi
    tap "Enhance" && tap "Auto enhance" && sleep 1 && wait_idle
    tap "Filters" && sleep 1 && tap "Vivid" && sleep 1 && wait_idle
    tap "Save" && sleep 2 && wait_idle
    log "release: after save: $(grep -oE 'text="Saved[^"]*"' "$UI" | head -1)"
  else
    log "release: the sample photo did not appear in MediaStore"
  fi
  alive && log "release: app still running" || log "release: APP NOT RUNNING"
fi

adb logcat -d -v time > "$OUT/logcat.txt" 2>&1
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  log "CRASH in logcat:"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 >> "$LOG"
else
  log "no crash in logcat"
fi
rm -f "$UI"
exit 0
