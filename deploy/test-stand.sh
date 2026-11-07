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

# --- APK-хост (nginx, 8443): только GET/HEAD, только *.apk и *.sha256, TLS 1.2+ ---
apk=apk/waiter-4.13.apk
[ -f "$apk" ] && [ -f "$apk.sha256" ] || fail "сначала tools/dev/build-dummy-apk.sh 13"
apk_url=https://localhost:8443
acurl() { curl -sS --max-time 10 --cacert certs/out/ca.crt "$@"; }
code() { acurl -o /dev/null -w '%{http_code}' "$@" || true; }

want_sha="$(awk '{print $1}' "$apk.sha256")"
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
acurl "$apk_url/waiter-4.13.apk" -o "$tmp" || fail "GET waiter-4.13.apk over TLS failed"
if command -v sha256sum >/dev/null; then got_sha="$(sha256sum "$tmp" | awk '{print $1}')"; else got_sha="$(shasum -a 256 "$tmp" | awk '{print $1}')"; fi
[ "$got_sha" = "$want_sha" ] || fail "downloaded APK sha256 differs from waiter-4.13.apk.sha256"
[ "$(acurl -o /dev/null -w '%{content_type}' "$apk_url/waiter-4.13.apk")" = "application/vnd.android.package-archive" ] \
  || fail "APK content-type is not application/vnd.android.package-archive"
acurl "$apk_url/waiter-4.13.apk.sha256" | grep -q "$want_sha" || fail ".sha256 is not served"
for m in POST PUT DELETE; do
  case "$(code -X "$m" "$apk_url/waiter-4.13.apk")" in 403|405) ;; *) fail "$m was not rejected with 403/405" ;; esac
done
case "$(code "$apk_url/")" in 403|404) ;; *) fail "GET / is a listing or 200" ;; esac
[ "$(code "$apk_url/secret.txt")" = 404 ] || fail "GET /secret.txt is not 404"
if acurl -D - -o /dev/null "$apk_url/waiter-4.13.apk" | grep -qiE '^server:.*[0-9]+\.[0-9]+'; then fail "nginx version is exposed in Server header"; fi
[ "$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' http://localhost:8443/ || true)" != 200 ] || fail "plain HTTP on the TLS port returned 200"
if nc -z -w 2 localhost 80 2>/dev/null; then fail "port 80 is open"; fi
# TLS 1.1 не принимается (если локальный curl/OpenSSL его вообще умеет, иначе проверка пропускается)
if acurl --tlsv1.0 --tls-max 1.1 -o /dev/null "$apk_url/waiter-4.13.apk" 2>/dev/null; then fail "TLS 1.1 accepted"; fi
acurl --tlsv1.2 --tls-max 1.2 -o /dev/null "$apk_url/waiter-4.13.apk" || fail "TLS 1.2 is not accepted"
# Ключ CA не попадает в контейнер, ключ сервера недоступен на запись
if docker compose exec -T apk-host sh -c 'find / \( -path /proc -o -path /sys \) -prune -o -name "ca.key" -print 2>/dev/null | grep -q .'; then
  fail "ca.key is visible inside the apk-host container"
fi
[ "$(docker compose exec -T apk-host sh -c 'touch /srv/apk/x 2>&1 || echo ro' | tail -1 | tr -d '\r')" = ro ] || fail "/srv/apk is writable in apk-host"

docker compose restart mosquitto >/dev/null
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q mosquitto)")" = "healthy" ] && break
  sleep 1
done
sub -u admin -P "$DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1 -W 5 | grep -q 'mosquitto version 2.1' \
  || fail "admin lost after restart (DynSec/persistence volume)"

echo "stand: all checks passed"
