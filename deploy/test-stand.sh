#!/usr/bin/env bash
# Smoke-тест стенда: TLS, запрет анонимов, DynSec-админ, отсутствие plain-порта, persistence после рестарта.
# Предусловия: make-ca.sh выполнен, deploy/.env заполнен, стенд поднят (см. deploy/README.md).
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck disable=SC1091
source .env

fail() { echo "FAIL: $*" >&2; exit 1; }
sub() { docker compose exec -T mosquitto mosquitto_sub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt "$@"; }

echo | openssl s_client -connect localhost:8883 -CAfile certs/out/ca.crt -verify_return_error -servername localhost >/dev/null 2>&1 \
  || fail "TLS handshake on localhost:8883 with stand CA failed"

# Брокер должен отказать на уровне CONNACK, а не просто не доставить сообщения
refused() {
  local out
  out="$(sub "$@" -t 'rf/v1/#' -C 1 -W 3 2>&1 || true)"
  grep -qiE 'not authori[sz]ed|bad user name or password' <<<"$out"
}
refused || fail "anonymous client was not refused with CONNACK 'not authorised'"
refused -u admin -P "wrong-$DYNSEC_ADMIN_PASSWORD" || fail "wrong password was not refused"
sub -u admin -P "$DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1 -W 5 | grep -q 'mosquitto version 2.1' \
  || fail "admin cannot read \$SYS/broker/version"

if nc -z -w 2 localhost 1883 2>/dev/null; then fail "plain MQTT port 1883 is open"; fi

# Ключ CA не попадает в контейнер брокера; ключ сервера доступен только пользователю mosquitto
cid="$(docker compose ps -q mosquitto)"
if docker compose exec -T mosquitto sh -c 'find / \( -path /proc -o -path /sys \) -prune -o -name "ca.key" -print 2>/dev/null | grep -q .'; then
  fail "ca.key is visible inside the broker container"
fi
[ "$(docker compose exec -T mosquitto stat -c '%a %U' /mosquitto/certs/server.key | tr -d '\r')" = "600 mosquitto" ] \
  || fail "server.key inside the container is not 600 mosquitto"
# Пароль админа DynSec не нужен брокеру: его нет ни в окружении, ни в healthcheck
if docker inspect -f '{{json .Config.Env}} {{json .Config.Healthcheck}}' "$cid" | grep -q 'DYNSEC_ADMIN_PASSWORD'; then
  fail "DYNSEC_ADMIN_PASSWORD is exposed in the broker container env/healthcheck"
fi

docker compose restart mosquitto >/dev/null
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q mosquitto)")" = "healthy" ] && break
  sleep 1
done
sub -u admin -P "$DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1 -W 5 | grep -q 'mosquitto version 2.1' \
  || fail "admin lost after restart (DynSec/persistence volume)"

echo "stand: all checks passed"
