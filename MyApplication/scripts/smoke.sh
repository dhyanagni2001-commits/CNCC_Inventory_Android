#!/bin/bash
# Smoke test of the optimised (R8) build on the connected phone/emulator: catches breakage that only
# appears after shrinking, which the debug test suite cannot see. Read-only: it installs over the
# app (data kept), opens screens, starts the scanner and builds the Excel report (save is cancelled).
#   scripts/smoke.sh              build + install preview, then test
#   APK=path/to.apk scripts/smoke.sh   test a given APK instead (e.g. the signed release)
# Exit code 0 = pass. Report: test-results/smoke-<timestamp>.txt
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.cnanjappa.inventory
mkdir -p test-results
OUT="test-results/smoke-$(date +%Y%m%d-%H%M).txt"
FAILS=0
log() { echo "$*" | tee -a "$OUT"; }
check() { if eval "$2"; then log "PASS  $1"; else log "FAIL  $1"; FAILS=$((FAILS + 1)); fi; }
screen() { $ADB shell uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1; $ADB exec-out cat /sdcard/smoke.xml; }
has() { screen | grep -q "text=\"$1"; }
tap() {  # tap the centre of the first element whose text (or, with ATTR=content-desc, description) starts with $1
  local b; b=$(screen | grep -oE "${ATTR:-text}=\"$1[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}

if [ -z "${APK:-}" ]; then ./gradlew -q :app:assemblePreview; APK=app/build/outputs/apk/preview/app-preview.apk; fi
log "# Smoke test $(date)  device: $($ADB shell getprop ro.product.model | tr -d '\r') Android $($ADB shell getprop ro.build.version.release | tr -d '\r')  apk: $APK"
$ADB install -r "$APK" >/dev/null
$ADB shell pm grant $PKG android.permission.CAMERA 2>/dev/null || true
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am force-stop $PKG
$ADB logcat -c
$ADB shell am start -n $PKG/.MainActivity >/dev/null
sleep 5
log "Version: $($ADB shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')"

check "Launch: home screen shown" 'has "Pieces in stock"'
ATTR=content-desc tap "Menu"; sleep 1
check "Menu shows app version" 'has "Version "'
$ADB shell input keyevent KEYCODE_BACK; sleep 1

tap "Products"; sleep 2
check "Products tab opens (database read)" 'has "Search name"'
tap "Stock"; sleep 2
check "Stock tab opens (size totals)" 'has "Stock overview"'
tap "Home"; sleep 2

tap "Sell"; sleep 8
CAMS=$($ADB shell dumpsys media.camera | grep -m1 "Number of camera devices" | grep -oE '[0-9]+' || echo 0)
if [ "$CAMS" -gt 0 ]; then
  check "Scanner: camera starts" '! has "Camera unavailable" && ! has "No camera found" && has "Scanning here"'
  check "Scanner: frames decode without errors" '! $ADB logcat -d -s BarcodeAnalyzer:W CameraScanner:W | grep -q " W "'
else
  check "Scanner: no-camera message" 'has "No camera found"'
fi
$ADB shell input keyevent KEYCODE_BACK; sleep 2

tap "Stock overview"; sleep 8
check "Excel report builds (save dialog opens)" '$ADB shell dumpsys window | grep -m1 mCurrentFocus | grep -qi documentsui || has "Excel saved"'
$ADB shell input keyevent KEYCODE_BACK; sleep 2   # cancel the save dialog
check "Cancelling the save leaves the app usable" 'has "Excel not saved" && has "Save the full stock report"'
tap "OK"; sleep 1

check "No crash in the whole run" '! $ADB logcat -d | grep -q "FATAL EXCEPTION"'
log
if [ $FAILS -eq 0 ]; then log "RESULT: PASS"; else log "RESULT: FAIL ($FAILS)"; $ADB logcat -d | grep -A15 "FATAL EXCEPTION" | head -40 >> "$OUT" || true; exit 1; fi
