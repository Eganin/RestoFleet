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

docker compose restart mosquitto >/dev/null
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q mosquitto)")" = "healthy" ] && break
  sleep 1
done
sub -u admin -P "$DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1 -W 5 | grep -q 'mosquitto version 2.1' \
  || fail "admin lost after restart (DynSec/persistence volume)"

echo "stand: all checks passed"
