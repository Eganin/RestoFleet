#!/usr/bin/env bash
# CA стенда RestoFleet и серверный сертификат (Mosquitto, apk-host; SAN toxiproxy — клиенты через прокси деградации).
#   ./make-ca.sh [LAN_IP]      LAN_IP по умолчанию — адрес en0 (нужен реальным устройствам в MVP-B)
# Результат — в $OUT_DIR (по умолчанию deploy/certs/out, в .gitignore):
#   ca.key (600), ca.crt — CA на 10 лет; создаётся один раз и при повторном запуске НЕ перевыпускается;
#   server.key, server.crt — ≤ 825 дней, EKU serverAuth, SAN ниже; перевыпускается при каждом запуске;
#   public/ca.crt — единственный файл, который уходит в агент (assets) и клиентам;
#   broker/ — то, что монтируется в контейнер брокера: ca.crt, server.crt, server.key (ключа CA там нет).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${OUT_DIR:-$here/out}"
lan_ip="${1:-$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || true)}"
[ -n "$lan_ip" ] || { echo "LAN_IP не задан и не определён автоматически" >&2; exit 1; }

mkdir -p "$out/public" "$out/broker"
umask 077

if [ ! -s "$out/ca.key" ] || [ ! -s "$out/ca.crt" ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$out/ca.key"
  openssl req -x509 -new -key "$out/ca.key" -sha256 -days 3650 \
    -subj "/O=RestoFleet/CN=RestoFleet Stand CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$out/ca.crt"
  echo "CA создан: $out/ca.crt"
else
  echo "CA уже есть, не перевыпускается: $out/ca.crt"
fi
chmod 600 "$out/ca.key"

ext="$(mktemp)"
trap 'rm -f "$ext" "$out/server.csr"' EXIT
cat >"$ext" <<CONF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,DNS:mosquitto,DNS:apk-host,DNS:backend,DNS:toxiproxy,IP:127.0.0.1,IP:10.0.2.2,IP:${lan_ip}
CONF

openssl ecparam -name prime256v1 -genkey -noout -out "$out/server.key"
openssl req -new -key "$out/server.key" -subj "/O=RestoFleet/CN=restofleet-stand" -out "$out/server.csr"
openssl x509 -req -in "$out/server.csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -CAcreateserial \
  -days 825 -sha256 -extfile "$ext" -out "$out/server.crt" 2>/dev/null
chmod 600 "$out/server.key"
chmod 644 "$out/server.crt" "$out/ca.crt"
# Контейнер копирует ключ себе с владельцем mosquitto и правами 600 (см. docker-compose.yml)
cp -p "$out/ca.crt" "$out/server.crt" "$out/server.key" "$out/broker/"

umask 022
cp "$out/ca.crt" "$out/public/ca.crt"
echo "Серверный сертификат: $out/server.crt (SAN: localhost, mosquitto, apk-host, backend, toxiproxy, 127.0.0.1, 10.0.2.2, ${lan_ip})"
