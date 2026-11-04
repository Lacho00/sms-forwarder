#!/usr/bin/env bash
# End-to-end send check on an emulator: seeds two destinations (one OK, one failing) and a rule through the debug-only
# SeedReceiver, injects an SMS and waits for one delivery sent and one in error. Prints PASS or FAIL.
# Usage: scripts/e2e-emulator.sh [--no-install]
set -euo pipefail

export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
case "$ANDROID_SERIAL" in
  emulator-*) ;;
  *) echo "refusing to run: ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator" >&2; exit 2 ;;
esac

OK_URL="${E2E_OK_URL:-https://httpbin.org/anything}"
FAIL_URL="${E2E_FAIL_URL:-https://httpbin.org/status/403}"
PKG=dev.smsforwarder.debug
RECEIVER="$PKG/dev.smsforwarder.diag.SeedReceiver"
TIMEOUT_S=90

cd "$(dirname "$0")/.."

timeout 60 adb wait-for-device

if [ "${1:-}" != "--no-install" ]; then
  ./gradlew :app:installDebug
fi

# Single-quotes one argument for the device shell.
quote() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

# seed key=value...: sends the broadcast, prints the result data, fails when the result code is not 0.
seed() {
  local cmd="am broadcast -a dev.smsforwarder.debug.SEED -n $RECEIVER" kv
  for kv in "$@"; do
    cmd+=" --es $(quote "${kv%%=*}") $(quote "${kv#*=}")"
  done
  local out code data
  out="$(timeout 20 adb shell "$cmd" | tr -d '\r')"
  code="$(sed -n 's/.*result=\([0-9-]*\).*/\1/p' <<<"$out" | head -n1)"
  data="$(sed -n 's/.*data="\(.*\)".*/\1/p' <<<"$out" | head -n1)"
  if [ "$code" != "0" ]; then
    echo "seed failed: ${data:-$out}" >&2
    return 1
  fi
  printf '%s\n' "$data"
}

id_of() {
  local id
  id="$(sed -n 's/^id=//p' <<<"$1")"
  if [ -z "$id" ]; then echo "seed returned no id: ${1:-empty}" >&2; exit 1; fi
  printf '%s\n' "$id"
}

timeout 20 adb shell pm clear "$PKG" >/dev/null
for p in RECEIVE_SMS READ_SMS POST_NOTIFICATIONS; do
  timeout 20 adb shell pm grant "$PKG" "android.permission.$p"
done

BODY='{"b":"{body}"}'
seed op=done >/dev/null
out="$(seed op=destination template=custom name=Ok "url=$OK_URL" "body=$BODY" json=true)"
OK_ID="$(id_of "$out")"
out="$(seed op=destination template=custom name=Fail "url=$FAIL_URL" "body=$BODY" json=true)"
FAIL_ID="$(id_of "$out")"
seed op=rule name=E2E sender=TEST "destinations=$OK_ID,$FAIL_ID" >/dev/null

timeout 20 adb emu sms send TEST "e2e $(date +%s)" >/dev/null

deadline=$((SECONDS + TIMEOUT_S))
status=""
while [ "$SECONDS" -lt "$deadline" ]; do
  status="$(seed op=status || true)"
  case "$status" in
    *"sms queued=0 sent=0 error=1 "*"deliveries queued=0 sent=1 error=1 "*) echo "PASS ($status)"; exit 0 ;;
  esac
  sleep 3
done
echo "FAIL (last status: ${status:-none})"
exit 1
