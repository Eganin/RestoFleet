#!/usr/bin/env bash
# Прогон матрицы MVP-A на ОДНОМ AVD (заголовок результата - docs/mvp-results/api<N>-normal.json).
#
#   run-matrix.sh <avd> <api> [--no-reboot] [--no-start]
#
# Шаги: загрузка эмулятора (-no-window -no-audio -no-boot-anim -port 5600, один эмулятор) -> установка debug-агента ->
# учётка dev-emu-<api> через stdin (пароль не попадает в argv) -> am start -> ожидание retained online и одной
# телеметрии по подписке оператора (MQTT) -> L2: adb reboot, UI не открывается, ожидание retained online с новым
# connId (<= 180 с после boot_completed) -> нажатие «Экспорт» в Capability Lab (uiautomator) -> adb pull отчёта.
# Дописывает строки результата в docs/mvp-results/api<N>-summary.txt (без секретов). Эмулятор в конце гасится.
#
# Требуется: поднятый стенд (deploy/), deploy/out/dev-credentials.txt, собранный :agent:app:assembleDebug, docker,
# python3. В DEV_EXPORT-ресивера в агенте нет (Ruling), экспорт - кнопкой Lab. Пароли не печатаются.
set -euo pipefail

AVD="${1:?avd}"; API="${2:?api}"; shift 2
REBOOT=1; START=1
for a in "$@"; do case "$a" in --no-reboot) REBOOT=0;; --no-start) START=0;; esac; done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
PORT=5600; SER="emulator-$PORT"; DEV="dev-emu-$API"
APK="${APK:-$ROOT/agent/app/build/outputs/apk/debug/app-debug.apk}"
OUT="$ROOT/docs/mvp-results"; mkdir -p "$OUT"
SUM="$OUT/api$API-summary.txt"; : >"$SUM"
A() { adb -s "$SER" "$@"; }
O() { docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T -e XDG_CONFIG_HOME=/tmp/rfopt mosquitto "$@"; }
say() { echo "$*" | tee -a "$SUM"; }
jget() { python3 -I -c '
import json,sys
try: print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except ValueError: print("")' "$1"; }

cleanup() {
  A emu kill >/dev/null 2>&1 || true
  docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T mosquitto rm -rf /tmp/rfopt >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- опции оператора в контейнере (пароль только через stdin) ---
OP_PW="$(awk '$1=="operator"{print $2}' "$ROOT/deploy/out/dev-credentials.txt")"
{ printf 'umask 077\nmkdir -p /tmp/rfopt\n'
  for t in mosquitto_sub mosquitto_pub; do
    printf "cat > /tmp/rfopt/%s <<'EOF'\n-h localhost\n-p 8883\n-V mqttv5\n--cafile /mosquitto/certs/ca.crt\n-u operator\n-P %s\nEOF\n" "$t" "$OP_PW"
  done; } | docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T mosquitto sh -s
unset OP_PW

# --- эмулятор ---
if (( START )); then
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -port "$PORT" >"$OUT/emulator-$AVD.log" 2>&1 &
fi
wait_boot() {
  A wait-for-device
  until [[ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 3; done
}
T0=$(date +%s); wait_boot
say "avd=$AVD api=$API sdk=$(A shell getprop ro.build.version.sdk | tr -d '\r') boot_s=$(( $(date +%s) - T0 ))"
A shell input keyevent 82 >/dev/null 2>&1 || true

A install -r -t "$APK" >/dev/null && say "install=ok"

status() { O mosquitto_sub -i "m4-st-$$" -t "rf/v1/dev/$DEV/status" -C 1 -W "${1:-10}" -F '%p' 2>/dev/null || true; }
wait_online() { # $1=старый connId, $2=дедлайн в секундах; печатает connId
  local end=$(( $(date +%s) + $2 )) p cid st
  while (( $(date +%s) < end )); do
    p="$(status 8)"
    if [[ -n "$p" ]]; then
      st="$(jget state <<<"$p")"; cid="$(jget connId <<<"$p")"
      if [[ "$st" == online && -n "$cid" && "$cid" != "$1" ]]; then echo "$cid"; return 0; fi
    fi
  done
  return 1
}

OLD="$(jget connId <<<"$(status 5)")"   # retained от прошлых сессий: новый online обязан отличаться

# --- учётка: скрипт собирается в stdin, пароль в argv не попадает (printf - builtin) ---
PW="$(awk -v u="$DEV" '$1==u{print $2}' "$ROOT/deploy/out/dev-credentials.txt")"
printf 'am broadcast -a ru.restofleet.agent.DEV_CONFIG -n ru.restofleet.agent/.DevConfigReceiver --es host 10.0.2.2 --ei port 8883 --es deviceId %s --es password %s\n' "$DEV" "$PW" \
  | A shell sh | grep -o 'result=[-0-9]*\|data="[a-z_]*"' | tr '\n' ' ' | sed 's/^/devconfig: /' | tee -a "$SUM"; echo | tee -a "$SUM" >/dev/null
unset PW


A shell am start -n ru.restofleet.agent/.MainActivity >/dev/null
T1=$(date +%s)
if CID="$(wait_online "$OLD" 120)"; then
  say "mqtt_online=ok connId=$CID after_s=$(( $(date +%s) - T1 )) mode=$(jget mode <<<"$(status 5)")"
else
  say "mqtt_online=FAIL (нет retained online за 120 с)"
fi
TEL="$(O mosquitto_sub -i "m4-tel-$$" -t "rf/v1/dev/$DEV/telemetry" -C 1 -W 70 -F '%p' 2>/dev/null || true)"
if [[ -n "$TEL" ]]; then say "mqtt_telemetry=ok seq=$(jget seq <<<"$TEL") ts=$(jget ts <<<"$TEL")"; else say "mqtt_telemetry=FAIL (нет телеметрии за 70 с)"; fi

# --- L2: перезагрузка без открытия UI ---
if (( REBOOT )); then
  PREV="${CID:-$OLD}"
  TR=$(date +%s); A reboot; sleep 5; wait_boot; TB=$(date +%s)
  if CID2="$(wait_online "$PREV" 180)"; then
    say "L2_reboot=ok boot_s=$(( TB - TR )) online_after_boot_s=$(( $(date +%s) - TB )) connId=$CID2"
  else
    say "L2_reboot=FAIL (нет online с новым connId за 180 с после boot_completed, boot_s=$(( TB - TR )))"
    A logcat -d -t 400 2>/dev/null | grep -i "restofleet\|AgentMqtt\|ForegroundService" | tail -30 >"$OUT/api$API-reboot-logcat.txt" || true
  fi
  A shell am start -n ru.restofleet.agent/.MainActivity >/dev/null; sleep 5
fi

# --- экспорт Lab: uiautomator -> узел «Экспорт» -> tap ---
tap() {
  A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  A shell cat /sdcard/ui.xml | python3 -I -c '
import sys,re
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r"<node[^>]*>",x):
    n=m.group(0); tx=re.search(r"text=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if tx and tx.group(1)==t and b:
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); sys.exit(0)
sys.exit(1)' "$1" | { read -r x y && A shell input tap "$x" "$y"; }
}
REMOTE="/sdcard/Android/data/ru.restofleet.agent/files/capability-report-api$API-normal.json"
A shell rm -f "$REMOTE" >/dev/null 2>&1 || true
tap "Экспорт" && say "export_tap=ok"
for _ in $(seq 1 40); do A shell ls "$REMOTE" >/dev/null 2>&1 && break; sleep 3; done
if A pull "$REMOTE" "$OUT/api$API-normal.json" >/dev/null 2>&1; then
  say "export_pull=ok file=docs/mvp-results/api$API-normal.json"
else
  say "export_pull=FAIL"
fi
