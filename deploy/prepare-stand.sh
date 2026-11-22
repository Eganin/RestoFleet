#!/usr/bin/env bash
# Подготовка стенда «с нуля» (КМ2b, план 2.4) — идемпотентно, секреты не печатаются:
#   1. deploy/.env с паролем admin DynSec (если файла нет);
#   2. CA и серверный сертификат (если нет или в SAN нет toxiproxy — тогда перевыпуск, CA не меняется);
#   3. секреты deploy/out/{postgres-password,jwt-secret,admin-password} (600; существующие не трогаются);
#   4. DynSec: admin (dynsec-init), брокер, роли device/backend-service и учётка backend (mosquitto/dynsec-dev.sh);
#   5. образ бэкенда: ./gradlew :server:app:installDist + docker compose build backend.
# После него весь стенд поднимается одной командой: docker compose --profile dev up -d --wait
set -euo pipefail
cd "$(dirname "$0")"

log() { printf '== %s\n' "$*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
command -v openssl >/dev/null || fail "нужен openssl"
docker info >/dev/null 2>&1 || fail "Docker не запущен"

umask 077
mkdir -p out
chmod 700 out

# 1. .env
if [ ! -s .env ]; then
  pw="$(openssl rand -base64 32 | tr -d '/+=\n')"
  # Подстановка встроенными средствами bash: пароль не попадает в argv процессов
  while IFS= read -r line || [ -n "$line" ]; do printf '%s\n' "${line//change-me/$pw}"; done <.env.example >.env
  unset pw
  chmod 600 .env
  log ".env создан (DYNSEC_ADMIN_PASSWORD сгенерирован)"
fi

# 2. Сертификаты
reissued=0
if [ ! -s certs/out/server.crt ] || ! openssl x509 -in certs/out/server.crt -noout -ext subjectAltName 2>/dev/null \
  | grep -q 'DNS:toxiproxy'; then
  ./certs/make-ca.sh "$@" >/dev/null
  reissued=1
  log "серверный сертификат выпущен (CA: certs/out/public/ca.crt)"
fi

# 3. Секреты бэкенда: только если файла нет. Пароль БД действует с первой инициализации тома postgres-data —
#    новый файл при живом томе не совпадёт с паролем в БД (тогда: docker compose down -v postgres … см. README).
secret() {
  local file="out/$1" bytes="$2"
  if [ ! -s "$file" ]; then
    openssl rand -base64 "$bytes" | tr -d '\n/+=' >"$file"
    printf '\n' >>"$file"
    chmod 600 "$file"
    log "секрет out/$1 создан"
  fi
}
secret postgres-password 32
secret jwt-secret 48
secret admin-password 24

# 4. DynSec
docker compose --profile init run --rm dynsec-init >/dev/null
docker compose up -d --wait mosquitto apk-host >/dev/null
if [ "$reissued" = 1 ]; then
  docker compose restart mosquitto >/dev/null
  docker compose up -d --wait mosquitto >/dev/null
fi
# nginx читает конфигурацию при старте: пересоздание подхватывает новый apk-host.conf и сертификат
docker compose up -d --wait --force-recreate apk-host >/dev/null
./mosquitto/dynsec-dev.sh >/dev/null
[ -s out/backend-mqtt-password ] || fail "нет out/backend-mqtt-password (см. README, «Учётки устройств»)"
log "DynSec: роли и учётка backend готовы"

# 5. Образ бэкенда
(cd .. && ./gradlew -q :server:app:installDist)
docker compose build -q backend
log "образ restofleet/backend:dev собран"

log "готово: docker compose --profile dev up -d --wait && ./test-backend.sh"
