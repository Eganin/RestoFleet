#!/usr/bin/env bash
# Smoke бэкенда на живом стенде (КМ2b, план 2.4; приёмка КМ2). Предусловие — стенд поднят:
#   ./prepare-stand.sh && docker compose --profile dev up -d --wait
# Сценарий: health через nginx (HTTPS, CA стенда) → вход admin → ресторан → код → POST /provision →
# status + телеметрия учёткой устройства по MQTT (TLS, внутри контейнера mosquitto) → устройство online и телеметрия
# в API → PUT shadow → retained desired у брокера → команда → cmd/req у устройства → cmd/res → succeeded →
# вывод из эксплуатации → учётка не подключается → перезапуск брокера: бэкенд переподключается → в логах бэкенда и
# в выводе нет ни одного секрета (числом). Перезапуск брокера рвёт и другие MQTT-сессии стенда (агенты переподключатся).
# Пароли и токены — только в файлах 600 во временном каталоге и в контейнере брокера на время проверки; в argv
# процессов хоста они не попадают (printf и подстановки — встроенные в bash, curl читает заголовки и тела из файлов).
set -euo pipefail
cd "$(dirname "$0")"

BASE="${RF_BASE_URL:-https://localhost:8443}"
CA=certs/out/public/ca.crt
tmp="$(mktemp -d)"
chmod 700 "$tmp"
umask 077
log="$tmp/out.log"
broker_opts=/tmp/rfsmoke
passed=0

cleanup() {
  docker compose exec -T mosquitto rm -rf "$broker_opts" >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

say() { printf '%s\n' "$*" | tee -a "$log"; }
pass() { passed=$((passed + 1)); say "PASS $passed: $*"; }
fail() { say "FAIL: $*"; exit 1; }

# JSON-поле из файла: python3 -I, путь через точку, индексы — числа
jget() { python3 -I -c 'import json,sys
v=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."):
    v=v[int(k)] if isinstance(v,list) else v.get(k) if isinstance(v,dict) else None
    if v is None: break
print("" if v is None else (json.dumps(v) if isinstance(v,(dict,list)) else str(v).lower() if isinstance(v,bool) else v))' "$1" "$2"; }

# api METHOD PATH [файл тела] [auth] → код ответа; тело ответа — в $tmp/resp
api() {
  local method="$1" path="$2" body="${3:-}" auth="${4:-}"
  local args=(-sS --max-time 15 --cacert "$CA" -o "$tmp/resp" -w '%{http_code}' -X "$method")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  [ -n "$auth" ] && args+=(-H "@$tmp/auth-header")
  curl "${args[@]}" "$BASE$path" || echo 000
}

# Учётка устройства — в файлы опций mosquitto_pub/mosquitto_sub внутри контейнера брокера (через stdin)
broker_login() {
  {
    printf 'umask 077\nmkdir -p %s\n' "$broker_opts"
    for tool in mosquitto_pub mosquitto_sub; do
      printf "cat > %s/%s <<'EOF'\n-h localhost\n-p 8883\n-V mqttv5\n--cafile /mosquitto/certs/ca.crt\n-u %s\n-P %s\n-i %s\nEOF\n" \
        "$broker_opts" "$tool" "$device_id" "$(cat "$tmp/device-password")" "$device_id"
    done
  } | docker compose exec -T mosquitto sh -s
}
as_device() { docker compose exec -T -e XDG_CONFIG_HOME="$broker_opts" mosquitto "$@"; }

[ -s out/admin-password ] || fail "нет deploy/out/admin-password (./prepare-stand.sh)"
[ -s "$CA" ] || fail "нет $CA (./prepare-stand.sh)"

# --- 1. health через nginx: бэкенд, БД и брокер (TLS backend → mosquitto:8883) ---
code="$(api GET /health)"
[ "$code" = 200 ] || fail "GET /health → $code (бэкенд не поднят или нездоров)"
[ "$(jget "$tmp/resp" components.database)" = true ] && [ "$(jget "$tmp/resp" components.broker)" = true ] \
  || fail "health: $(cat "$tmp/resp")"
pass "GET /health через nginx 8443: database и broker в порядке"
[ "$(api GET /api/v1/devices)" = 401 ] || fail "GET /devices без токена не 401"
pass "без токена — 401"

# --- 2. вход admin ---
printf '{"login":"admin","password":"%s"}' "$(cat out/admin-password)" >"$tmp/login.json"
[ "$(api POST /api/v1/auth/login "$tmp/login.json")" = 200 ] || fail "вход admin: $(jget "$tmp/resp" code)"
printf 'Authorization: Bearer %s\n' "$(jget "$tmp/resp" accessToken)" >"$tmp/auth-header"
jget "$tmp/resp" accessToken >"$tmp/access-token"
jget "$tmp/resp" refreshToken >"$tmp/refresh-token"
pass "вход admin (JWT)"

# --- 3. ресторан и код регистрации ---
suffix="$(date +%s)"
printf '{"code":"SMOKE-%s","name":"Smoke","city":"Тест","timezone":"Europe/Moscow"}' "$suffix" >"$tmp/restaurant.json"
[ "$(api POST /api/v1/restaurants "$tmp/restaurant.json" auth)" = 201 ] || fail "ресторан: $(cat "$tmp/resp")"
restaurant_id="$(jget "$tmp/resp" id)"
pass "ресторан SMOKE-$suffix создан"
printf '{"restaurantId":"%s","role":"kitchen","name":"Smoke-кухня"}' "$restaurant_id" >"$tmp/token.json"
[ "$(api POST /api/v1/provisioning/tokens "$tmp/token.json" auth)" = 201 ] || fail "код: $(jget "$tmp/resp" code)"
jget "$tmp/resp" code >"$tmp/provisioning-code"
pass "код регистрации выпущен"

# --- 4. провижининг (как агент: HTTPS через nginx с CA стенда, без JWT) ---
printf '{"code":"%s","deviceInfo":{"model":"smoke","manufacturer":"RestoFleet","osVersion":"16","apiLevel":36},"agentVersion":"smoke"}' \
  "$(cat "$tmp/provisioning-code")" >"$tmp/provision.json"
[ "$(api POST /api/v1/provision "$tmp/provision.json")" = 201 ] || fail "provision: $(jget "$tmp/resp" code)"
device_id="$(jget "$tmp/resp" deviceId)"
jget "$tmp/resp" password >"$tmp/device-password"
[ "$(jget "$tmp/resp" role)" = kitchen ] || fail "роль не kitchen"
[ "$(jget "$tmp/resp" mqtt.port)" = 8883 ] || fail "порт брокера не 8883"
pass "POST /provision → $device_id (kitchen, брокер $(jget "$tmp/resp" mqtt.host):8883)"
[ "$(api POST /api/v1/provision "$tmp/provision.json")" = 409 ] || fail "повторный provision тем же кодом не 409"
pass "погашенный код — 409 token_used"

# --- 5. MQTT учёткой устройства: status online + телеметрия ---
broker_login
now_ms="$(python3 -I -c 'import time; print(int(time.time()*1000))')"
printf '{"state":"online","ts":%s,"connId":"smoke-%s","agentVersion":"smoke","mode":"normal","device":{"model":"smoke","manufacturer":"RestoFleet","osVersion":"16","apiLevel":36}}' \
  "$now_ms" "$suffix" | as_device mosquitto_pub -t "rf/v1/dev/$device_id/status" -q 1 -r -s
printf '{"ts":%s,"seq":1,"battery":{"level":55,"charging":true,"tempC":31.5},"storage":{"freeMb":4096}}' "$now_ms" \
  | as_device mosquitto_pub -t "rf/v1/dev/$device_id/telemetry" -q 1 -s
pass "status online и телеметрия опубликованы учёткой устройства (TLS, clientId = deviceId)"

status=""
for _ in $(seq 1 30); do
  api GET "/api/v1/devices/$device_id" "" auth >/dev/null
  status="$(jget "$tmp/resp" device.status)"
  [ "$status" = online ] && [ -n "$(jget "$tmp/resp" lastTelemetry.ts)" ] && break
  sleep 0.5
done
[ "$status" = online ] || fail "устройство не online: $status"
pass "GET /devices/{id}: online, последняя телеметрия есть"
[ "$(api GET "/api/v1/devices/$device_id/telemetry?metric=batteryLevel" "" auth)" = 200 ] || fail "история телеметрии"
[ "$(jget "$tmp/resp" items.0.ts)" = "$now_ms" ] && [ "$(jget "$tmp/resp" items.0.batteryLevel)" = 55 ] \
  || fail "телеметрия не записана: $(cat "$tmp/resp")"
pass "телеметрия в GET /devices/{id}/telemetry (ts и заряд совпали)"

# --- 6. тень: PUT → retained desired у брокера ---
printf '{"telemetry":{"intervalSec":60}}' >"$tmp/shadow.json"
[ "$(api PUT "/api/v1/devices/$device_id/shadow" "$tmp/shadow.json" auth)" = 200 ] || fail "PUT shadow: $(cat "$tmp/resp")"
[ "$(jget "$tmp/resp" desiredVersion)" = 2 ] && [ "$(jget "$tmp/resp" published)" = true ] \
  || fail "PUT shadow: версия/публикация $(cat "$tmp/resp")"
pass "PUT shadow: desiredVersion 2, опубликовано"
as_device mosquitto_sub -t "rf/v1/dev/$device_id/shadow/desired" -C 1 -W 5 >"$tmp/desired.json" \
  || fail "retained desired не получен"
[ "$(jget "$tmp/desired.json" version)" = 2 ] && [ "$(jget "$tmp/desired.json" telemetry.intervalSec)" = 60 ] \
  || fail "retained desired: $(cat "$tmp/desired.json")"
pass "retained desired v2 (intervalSec 60) у брокера"

# --- 7. команда → cmd/req у устройства → cmd/res → succeeded ---
as_device mosquitto_sub -t "rf/v1/dev/$device_id/cmd/req" -q 1 -C 1 -W 20 >"$tmp/cmd-req.json" &
sub_pid=$!
sleep 2
printf '{"type":"ping","ttlSec":120}' >"$tmp/cmd.json"
[ "$(api POST "/api/v1/devices/$device_id/commands" "$tmp/cmd.json" auth)" = 201 ] || fail "команда: $(cat "$tmp/resp")"
cmd_id="$(jget "$tmp/resp" cmdId)"
wait "$sub_pid" || fail "cmd/req не пришёл подписчику-устройству"
[ "$(jget "$tmp/cmd-req.json" cmdId)" = "$cmd_id" ] && [ -n "$(jget "$tmp/cmd-req.json" expiresAt)" ] \
  || fail "cmd/req: $(cat "$tmp/cmd-req.json")"
pass "команда ping → cmd/req у устройства (cmdId, expiresAt)"
for st in received succeeded; do
  printf '{"cmdId":"%s","status":"%s","ts":%s,"result":{"deviceTime":"%s"}}' "$cmd_id" "$st" "$now_ms" "$now_ms" \
    | as_device mosquitto_pub -t "rf/v1/dev/$device_id/cmd/res" -q 1 -s
done
cmd_status=""
for _ in $(seq 1 30); do
  api GET "/api/v1/commands/$cmd_id" "" auth >/dev/null
  cmd_status="$(jget "$tmp/resp" status)"
  [ "$cmd_status" = succeeded ] && break
  sleep 0.5
done
[ "$cmd_status" = succeeded ] || fail "команда не succeeded: $cmd_status"
pass "cmd/res received + succeeded → GET /commands/{cmdId}: succeeded"

# --- 8. вывод из эксплуатации → учётка не подключается ---
[ "$(api DELETE "/api/v1/devices/$device_id" "" auth)" = 200 ] || fail "DELETE device: $(cat "$tmp/resp")"
[ "$(jget "$tmp/resp" device.status)" = decommissioned ] || fail "статус после вывода: $(cat "$tmp/resp")"
pass "DELETE /devices/{id}: decommissioned"
refused="$(as_device mosquitto_sub -t "rf/v1/dev/$device_id/cmd/req" -C 1 -W 5 2>&1 || true)"
grep -qiE 'not authori[sz]ed|bad user name or password|connection refused' <<<"$refused" \
  || fail "выведенная учётка подключилась: $(head -c 200 <<<"$refused")"
pass "выведенная учётка: брокер отказывает в подключении"

# --- 9. перезапуск брокера: бэкенд переподключается по TLS с той же учёткой и постоянной сессией ---
docker compose restart mosquitto >/dev/null 2>&1
broker=""
for _ in $(seq 1 60); do
  api GET /health >/dev/null
  broker="$(jget "$tmp/resp" components.broker)"
  [ "$broker" = true ] && break
  sleep 1
done
[ "$broker" = true ] || fail "после перезапуска брокера бэкенд не переподключился"
pass "перезапуск mosquitto: бэкенд переподключился (health broker = true)"

# --- 10. утечки: ни одного секрета в логах бэкенда и в выводе теста (только число) ---
patterns="$tmp/patterns"
{
  cat out/admin-password out/jwt-secret out/postgres-password out/backend-mqtt-password
  cat "$tmp/device-password" "$tmp/provisioning-code" "$tmp/access-token" "$tmp/refresh-token"
} | grep -v '^$' >"$patterns"
leaks="$(docker compose logs --no-color backend migrate 2>/dev/null | grep -cFf "$patterns" || true)"
own="$(grep -cFf "$patterns" "$log" || true)"
[ "$leaks" = 0 ] && [ "$own" = 0 ] || fail "секреты в логах: backend/migrate $leaks, вывод теста $own"
pass "утечек секретов: 0 (логи backend/migrate и вывод теста)"

say "test-backend.sh: $passed проверок PASS"
