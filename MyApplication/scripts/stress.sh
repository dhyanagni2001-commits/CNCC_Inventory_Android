#!/bin/bash
# 10,000-product stress run on the connected emulator/phone. Uses only the .debug test app
# (its data is cleared first); the shop app's data is never touched.
#   scripts/stress.sh [ui_seconds=300]
# Report: test-results/stress-<timestamp>.txt
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.cnanjappa.inventory.debug
UI_SECS=${1:-300}
mkdir -p test-results
OUT="test-results/stress-$(date +%Y%m%d-%H%M).txt"
log() { echo "$*" | tee -a "$OUT"; }
ticks() { $ADB shell "cat /proc/\$(pidof $PKG)/stat 2>/dev/null" | awk '{print $14+$15}'; }  # CPU ticks (1/100 s)

log "# Stress run $(date)  device: $($ADB shell getprop ro.product.model | tr -d '\r') API $($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
if [ -z "${SKIP_DB:-}" ]; then   # SKIP_DB=1 reruns phases 2-4 on the already seeded app
./gradlew -q :app:installDebug :app:installDebugAndroidTest
$ADB shell pm clear $PKG >/dev/null

log; log "## 1. Database stress: 10,000 products (seed, mixed ops, races, search, Excel, backup, reconciliation)"
$ADB logcat -c
RUNNER=$($ADB shell pm list instrumentation | grep "$PKG" | sed 's/instrumentation:\([^ ]*\).*/\1/' | tr -d '\r')
RES=$($ADB shell am instrument -w -e stress true -e class com.cnanjappa.inventory.StressTest "$RUNNER")
$ADB logcat -d -s STRESS:I -v raw | grep -v '^-' | tee -a "$OUT" || true
echo "$RES" | grep -q "OK (1 test)" && log "RESULT: PASS" || { log "RESULT: FAIL"; echo "$RES" | tail -20 | tee -a "$OUT"; exit 1; }
fi

log; log "## 2. UI on 10,000 products for ${UI_SECS}s (debug build: frame times are pessimistic vs release)"
$ADB shell dumpsys battery unplug
$ADB shell dumpsys batterystats --reset >/dev/null
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
$ADB shell dumpsys gfxinfo $PKG reset >/dev/null
T0=$(ticks); END=$((SECONDS + UI_SECS))
while [ $SECONDS -lt $END ]; do
  $ADB shell input tap 906 2202; sleep 1                       # Stock tab
  for _ in 1 2 3 4 5 6; do $ADB shell input swipe 540 1800 540 500 250; done
  $ADB shell input tap 540 900; sleep 0.5                      # expand a group
  for _ in 1 2 3; do $ADB shell input swipe 540 600 540 1800 250; done
  $ADB shell input tap 540 2202; sleep 1                       # Products tab
  for _ in 1 2 3 4 5 6; do $ADB shell input swipe 540 1800 540 500 120; done
  $ADB shell input tap 172 2202; sleep 1                       # Home
done
T1=$(ticks)
log "app CPU: $(awk -v t=$((T1 - T0)) -v s=$UI_SECS 'BEGIN{printf "%.1f%% of one core (%.1fs CPU in %ds)", t/s, t/100, s}')"
$ADB shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|percentile|Number Frame deadline missed" | sed 's/^ *//' | tee -a "$OUT"
$ADB shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|Java Heap|Native Heap|Graphics" | head -4 | sed 's/^ *//' | tee -a "$OUT"
APP_UID="u0a$(( $($ADB shell dumpsys package $PKG | grep -m1 -oE '(userId|appId)=[0-9]+' | cut -d= -f2) - 10000 ))"
log "emulator power-model estimate (placeholder profile, NOT real battery): $($ADB shell dumpsys batterystats | grep -m1 -E "^ *UID $APP_UID:" | sed 's/^ *//' || true)"
log "phone estimate from measured CPU: see README/report; CPU-seconds above are the real measured input"
log; log "## 3. Idle: app in background 60s (should use ~0 CPU, no wakelocks, camera closed)"
$ADB shell input keyevent KEYCODE_HOME
sleep 30; I0=$(ticks); sleep 60; I1=$(ticks)   # 30s settle after leaving
log "idle CPU: $(( (I1 - I0) )) ticks in 60s ($(awk -v t=$((I1 - I0)) 'BEGIN{printf "%.2f", t/60}')% of one core)"
WL=$($ADB shell dumpsys power | awk '/^Wake Locks: size/{f=1;next} f&&/^$/{f=0} f' | grep -c "$PKG" || true)
log "wakelocks currently held by app: $WL"
CAM=$($ADB shell dumpsys media.camera | awk '/^Active Camera Clients/{f=1;next} /^Allowed/{f=0} f' | grep -c "$PKG" || true)
log "camera currently open by app: $CAM"
$ADB shell dumpsys battery reset

log; log "## 4. Monkey: 3,000 random taps/swipes (crash/ANR check)"
M=$($ADB shell monkey -p $PKG --throttle 60 --pct-syskeys 0 --pct-appswitch 0 -s 20261008 -v 3000 2>&1 || true)
echo "$M" | grep -E "Events injected|CRASH|ANR|Monkey finished" | head -5 | tee -a "$OUT"
echo "$M" | grep -q "CRASH\|NOT RESPONDING" && log "monkey: FAIL" || log "monkey: PASS (no crash/ANR)"
log; log "Saved: $OUT"
