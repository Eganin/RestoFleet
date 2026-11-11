#!/usr/bin/env bash
# Прогон матрицы MVP-A на ОДНОМ AVD (заголовок результата - docs/mvp-results/api<N>-normal.json).
#
#   run-matrix.sh <avd> <api> [--window] [--do] [--no-reboot] [--no-start]
#   (--do: AVD с Device Owner, например rf-api36-do; учётка dev-do-<api>; итог - api<N>-do-summary.txt)
#
# Шаги: загрузка эмулятора (-no-window -no-audio -no-boot-anim -port 5600, один эмулятор; --window - с окном и звуком)
# -> установка debug-агента -> учётка dev-emu-<api> через stdin (пароль не попадает в argv) -> am start -> ожидание
# retained online и одной телеметрии по подписке оператора (MQTT) -> L2: adb reboot, UI не открывается, ожидание
# retained online с новым connId (<= 180 с после boot_completed) -> нажатие «Экспорт» в Capability Lab (uiautomator)
# -> adb pull отчёта. Дописывает строки результата в docs/mvp-results/api<N>-summary.txt (без секретов).
# Весь MQTT-трафик устройства за прогон (rf/v1/dev/<id>/#: status, telemetry, probe, events, shadow, cmd) пишется
# в logs/run-matrix/api<N>-<дата-время>-mqtt.log, строка на сообщение: «время топик payload»; рядом - лог эмулятора.
# --do сверх того: агент - Device Owner (при необходимости dpm set-device-owner), mode=device_owner в status, команды
# по MQTT: D3 install_update waiter-4.13 (с нуля) и 4.14 (обновление, SHA-256), D5 launch_app из фона, D2 reboot
# вместо adb reboot (итог идёт через outbox: до перезагрузки или после неё; заодно L2). Ответы ждутся по MQTT-логу.
# Эмулятор в конце гасится; если этот AVD уже запущен (например, из Android Studio), скрипт сразу останавливается.
#
# Требуется: поднятый стенд (deploy/), deploy/out/dev-credentials.txt, собранный :agent:app:assembleDebug, docker,
# python3. В DEV_EXPORT-ресивера в агенте нет (Ruling), экспорт - кнопкой Lab. Пароли не печатаются.
set -euo pipefail

AVD="${1:?avd}"; API="${2:?api}"; shift 2
REBOOT=1; START=1; WINDOW=0; DO=0
for a in "$@"; do case "$a" in
  --no-reboot) REBOOT=0;; --no-start) START=0;; --window) WINDOW=1;; --do) DO=1;;
  *) echo "неизвестный флаг: $a" >&2; exit 2;;
esac; done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
PORT=5600; SER="emulator-$PORT"
if (( DO )); then DEV="dev-do-$API"; MODE="device_owner"; SFX="-do"; else DEV="dev-emu-$API"; MODE="normal"; SFX=""; fi
APK="${APK:-$ROOT/agent/app/build/outputs/apk/debug/app-debug.apk}"
OUT="$ROOT/docs/mvp-results"; mkdir -p "$OUT"
SUM="$OUT/api$API$SFX-summary.txt"
A() { adb -s "$SER" "$@"; }
O() { docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T -e XDG_CONFIG_HOME=/tmp/rfopt mosquitto "$@"; }
say() { echo "$*" | tee -a "$SUM"; }
jget() { python3 -I -c '
import json,sys
try: print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except ValueError: print("")' "$1"; }

KILL_EMU=0; LOGSUB=""; OPTS=0
stop_log() { # останавливает подписку-лог: kill процесса внутри контейнера, затем ожидание локального docker exec
  [[ -n "$LOGSUB" ]] || return 0
  docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T mosquitto \
    sh -c 'p=$(cat /tmp/rfopt/logsub.pid 2>/dev/null) && kill "$p"' >/dev/null 2>&1 || true
  wait "$LOGSUB" 2>/dev/null || true
  LOGSUB=""
}
cleanup() {
  stop_log
  if (( KILL_EMU )); then A emu kill >/dev/null 2>&1 || true; fi
  if (( OPTS )); then
    docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T mosquitto rm -rf /tmp/rfopt >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# --- чужой или уже запущенный эмулятор: не ждать бесконечно и не гасить его в cleanup ---
if (( START )); then
  for s in $(adb devices | awk '/^emulator-/{print $1}'); do
    name="$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
    if [[ "$name" == "$AVD" || "$s" == "$SER" ]]; then
      echo "Эмулятор уже запущен: $s (AVD ${name:-?}). Закройте его (например, в Android Studio) и повторите." >&2
      exit 1
    fi
  done
fi
KILL_EMU=1; : >"$SUM"

# --- опции оператора в контейнере (пароль только через stdin) ---
OP_PW="$(awk '$1=="operator"{print $2}' "$ROOT/deploy/out/dev-credentials.txt")"
{ printf 'umask 077\nmkdir -p /tmp/rfopt\n'
  for t in mosquitto_sub mosquitto_pub; do
    printf "cat > /tmp/rfopt/%s <<'EOF'\n-h localhost\n-p 8883\n-V mqttv5\n--cafile /mosquitto/certs/ca.crt\n-u operator\n-P %s\nEOF\n" "$t" "$OP_PW"
  done; } | docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T mosquitto sh -s
unset OP_PW; OPTS=1

# --- лог всего MQTT-трафика устройства за прогон (retained status/desired приходят первыми) ---
LOGDIR="$ROOT/logs/run-matrix"; mkdir -p "$LOGDIR"
RUN_TS="$(date +%Y%m%d-%H%M%S)"; MQTT_LOG="$LOGDIR/api$API$SFX-$RUN_TS-mqtt.log"
z="$(date +%z)"; if [[ "${z:0:1}" == "+" ]]; then zs=-; else zs=+; fi
TZ_HOST="<$z>$zs${z:1:2}:${z:3:2}"   # POSIX TZ: время в логе - по часам хоста, а не UTC контейнера
docker compose -f "$ROOT/deploy/docker-compose.yml" exec -T -e XDG_CONFIG_HOME=/tmp/rfopt -e TZ="$TZ_HOST" mosquitto \
  sh -c 'echo $$ > /tmp/rfopt/logsub.pid; exec mosquitto_sub -i "$1" -t "$2" -F "%I %t %p"' \
  sh "rm-log-$$" "rf/v1/dev/$DEV/#" >"$MQTT_LOG" 2>&1 &
LOGSUB=$!

# --- эмулятор ---
EMU_ARGS=(-no-boot-anim -no-snapshot -port "$PORT")
if (( ! WINDOW )); then EMU_ARGS=(-no-window -no-audio "${EMU_ARGS[@]}"); fi
if (( START )); then
  nohup emulator -avd "$AVD" "${EMU_ARGS[@]}" >"$LOGDIR/api$API$SFX-$RUN_TS-emulator.log" 2>&1 &
fi
wait_boot() {
  A wait-for-device
  until [[ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 3; done
}
T0=$(date +%s); wait_boot
say "avd=$AVD api=$API sdk=$(A shell getprop ro.build.version.sdk | tr -d '\r') boot_s=$(( $(date +%s) - T0 ))"
A shell input keyevent 82 >/dev/null 2>&1 || true

A install -r -t "$APK" >/dev/null && say "install=ok"

# --- Device Owner (--do): уже назначен или назначить; без DO DO-проверки бессмысленны ---
is_owner() { # awk читает вывод целиком: без SIGPIPE под pipefail
  local d; d="$(A shell dumpsys device_policy 2>/dev/null | tr -d '\r' || true)"
  awk 'tolower($0) ~ /device owner/ {f = 4} f && /ru\.restofleet\.agent/ {ok = 1} f {f--} END {exit !ok}' <<<"$d"
}
if (( DO )); then
  if is_owner; then
    say "device_owner=ok (уже назначен)"
  else
    A shell dpm set-device-owner ru.restofleet.agent/.AdminReceiver >/dev/null 2>&1 || true
    if is_owner; then say "device_owner=ok (назначен через dpm set-device-owner)"
    else say "device_owner=FAIL (dpm set-device-owner не прошёл: на AVD есть аккаунты или второй пользователь?)"; exit 1; fi
  fi
fi

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
  M="$(jget mode <<<"$(status 5)")"
  say "mqtt_online=ok connId=$CID after_s=$(( $(date +%s) - T1 )) mode=$M"
  if [[ "$M" != "$MODE" ]]; then say "mode=FAIL (ожидался $MODE)"; fi
else
  say "mqtt_online=FAIL (нет retained online за 120 с)"
fi
TEL="$(O mosquitto_sub -i "m4-tel-$$" -t "rf/v1/dev/$DEV/telemetry" -C 1 -W 70 -F '%p' 2>/dev/null || true)"
if [[ -n "$TEL" ]]; then say "mqtt_telemetry=ok seq=$(jget seq <<<"$TEL") ts=$(jget ts <<<"$TEL")"; else say "mqtt_telemetry=FAIL (нет телеметрии за 70 с)"; fi

# --- команды по MQTT: публикация с MQTT 5 свойствами, итог (не received) ищется в MQTT-логе прогона ---
send_cmd() { # $1=метка (часть cmdId), $2=type, $3=params JSON, $4=срок в секундах; печатает cmdId
  local id="c-rm-$RUN_TS-$1"
  python3 -I -c '
import json,sys,time
i,t,p,ttl=sys.argv[1],sys.argv[2],json.loads(sys.argv[3]),int(sys.argv[4])
print(json.dumps({"cmdId":i,"type":t,"params":p,"expiresAt":int(time.time()*1000)+ttl*1000}))' "$id" "$2" "$3" "$4" \
    | O mosquitto_pub -i "rm-pub-$$" -q 1 -t "rf/v1/dev/$DEV/cmd/req" -D publish response-topic "rf/v1/dev/$DEV/cmd/res" \
        -D publish correlation-data "$id" -D publish message-expiry-interval "$4" -s >/dev/null
  echo "$id"
}
wait_res() { # $1=cmdId, $2=таймаут в секундах; печатает итоговый payload cmd/res
  local end=$(( $(date +%s) + $2 )) r
  while (( $(date +%s) < end )); do
    r="$(python3 -I -c '
import json,sys
want,topic,last=sys.argv[1],sys.argv[2],""
for line in open(sys.argv[3],encoding="utf-8",errors="replace"):
    f=line.rstrip("\n").split(" ",2)
    if len(f)<3 or f[1]!=topic: continue
    try: m=json.loads(f[2])
    except ValueError: continue
    if m.get("cmdId")==want and m.get("status")!="received": last=f[2]
print(last)' "$1" "rf/v1/dev/$DEV/cmd/res" "$MQTT_LOG")"
    if [[ -n "$r" ]]; then echo "$r"; return 0; fi
    sleep 2
  done
  return 1
}
res_brief() { python3 -I -c '
import json,sys
m=json.loads(sys.stdin.read()); o=["status="+str(m.get("status"))]
if m.get("reason"): o.append("reason="+str(m["reason"]))
if m.get("result"): o.append("result="+json.dumps(m["result"],ensure_ascii=False,separators=(",",":")))
print(" ".join(o))'; }

# --- DO-команды (--do): D3 установка и обновление, D5 запуск из фона ---
if (( DO )); then
  WPKG="ru.restofleet.dummy.waiter"
  ver() { local d; d="$(A shell dumpsys package "$WPKG" 2>/dev/null | tr -d '\r' || true)"
    awk -F= '/versionName=/ && v == "" {v = $2} END {print (v == "" ? "-" : v)}' <<<"$d"; }
  A uninstall "$WPKG" >/dev/null 2>&1 || true
  for v in 13 14; do
    label="D3_install_4.$v"; [[ "$v" == 14 ]] && label="D3_update_4.14"
    f="$ROOT/deploy/apk/waiter-4.$v.apk.sha256"
    if [[ ! -s "$f" ]]; then say "$label=SKIP (нет файла: tools/dev/build-dummy-apk.sh $v)"; continue; fi
    id="$(send_cmd "d3-4$v" install_update \
      "{\"package\":\"$WPKG\",\"url\":\"https://10.0.2.2:8443/waiter-4.$v.apk\",\"sha256\":\"$(awk '{print $1}' "$f")\"}" 300)"
    if r="$(wait_res "$id" 180)"; then say "$label: $(res_brief <<<"$r") installed=$(ver)"
    else say "$label=FAIL (нет итога cmd/res за 180 с) installed=$(ver)"; fi
  done
  A shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true; sleep 2
  id="$(send_cmd d5-launch launch_app "{\"pkg\":\"$WPKG\"}" 120)"
  if r="$(wait_res "$id" 60)"; then
    sleep 3
    d="$(A shell dumpsys activity activities 2>/dev/null | tr -d '\r' || true)"
    top="$(awk '/topResumedActivity|mResumedActivity/ && t == "" {t = $0} END {print t}' <<<"$d")"
    if [[ "$top" == *"$WPKG"* ]]; then fg=yes; else fg=no; fi
    say "D5_launch_bg: $(res_brief <<<"$r") foreground=$fg"
  else
    say "D5_launch_bg=FAIL (нет итога cmd/res за 60 с)"
  fi
  A shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
fi

# --- L2: перезагрузка без открытия UI (в --do - командой reboot по MQTT, это же D2) ---
boot_id() { A shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r' || true; }
if (( REBOOT )); then
  PREV="${CID:-$OLD}"
  if (( DO )); then
    BID0="$(boot_id)"; TR=$(date +%s)
    RID="$(send_cmd d2-reboot reboot '{}' 300)"
    until [[ "$(boot_id)" != "$BID0" && "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]] \
      || (( $(date +%s) > TR + 240 )); do sleep 3; done
  else
    TR=$(date +%s); A reboot; sleep 5; wait_boot
  fi
  TB=$(date +%s)
  if CID2="$(wait_online "$PREV" 180)"; then
    say "L2_reboot=ok boot_s=$(( TB - TR )) online_after_boot_s=$(( $(date +%s) - TB )) connId=$CID2"
  else
    say "L2_reboot=FAIL (нет online с новым connId за 180 с после boot_completed, boot_s=$(( TB - TR )))"
    A logcat -d -t 400 2>/dev/null | grep -i "restofleet\|AgentMqtt\|ForegroundService" | tail -30 \
      >"$LOGDIR/api$API$SFX-$RUN_TS-reboot-logcat.txt" || true
  fi
  if (( DO )); then
    if r="$(wait_res "$RID" 120)"; then say "D2_reboot_cmd: $(res_brief <<<"$r")"
    else say "D2_reboot_cmd=FAIL (нет итога cmd/res за 120 с после загрузки)"; fi
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
REMOTE="/sdcard/Android/data/ru.restofleet.agent/files/capability-report-api$API-$MODE.json"
A shell rm -f "$REMOTE" >/dev/null 2>&1 || true
tap "Экспорт" && say "export_tap=ok"
for _ in $(seq 1 40); do A shell ls "$REMOTE" >/dev/null 2>&1 && break; sleep 3; done
if A pull "$REMOTE" "$OUT/api$API-$MODE.json" >/dev/null 2>&1; then
  say "export_pull=ok file=docs/mvp-results/api$API-$MODE.json"
else
  say "export_pull=FAIL"
fi

# --- итог MQTT-лога: путь и число сообщений по типу топика ---
sleep 3; stop_log
say "mqtt_log=${MQTT_LOG#"$ROOT"/} $(awk '$2 ~ /^rf\/v1\/dev\// {
  n = split($2, a, "/"); k = a[5]; for (i = 6; i <= n; i++) k = k "/" a[i]; c[k]++; t++ }
  END { printf "messages=%d", t; for (k in c) printf " %s=%d", k, c[k] }' "$MQTT_LOG")"
