#!/usr/bin/env bash
# Проверка make-ca.sh: подпись, SAN, права ключа, идемпотентность CA.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

OUT_DIR="$tmp" "$here/make-ca.sh" 192.168.50.7 >/dev/null

openssl verify -CAfile "$tmp/ca.crt" "$tmp/server.crt" | grep -q ': OK$' || fail "server.crt is not signed by ca.crt"

san="$(openssl x509 -in "$tmp/server.crt" -noout -ext subjectAltName)"
for want in "DNS:localhost" "DNS:mosquitto" "DNS:apk-host" "DNS:backend" "DNS:toxiproxy" "IP Address:127.0.0.1" "IP Address:10.0.2.2" "IP Address:192.168.50.7"; do
  grep -q "$want" <<<"$san" || fail "SAN missing $want"
done

openssl x509 -in "$tmp/server.crt" -noout -ext extendedKeyUsage | grep -q "TLS Web Server Authentication" || fail "no serverAuth EKU"
openssl x509 -in "$tmp/ca.crt" -noout -ext basicConstraints | grep -q "CA:TRUE" || fail "ca.crt is not a CA"
[ "$(stat -f '%Lp' "$tmp/ca.key" 2>/dev/null || stat -c '%a' "$tmp/ca.key")" = "600" ] || fail "ca.key permissions are not 600"
cmp -s "$tmp/ca.crt" "$tmp/public/ca.crt" || fail "public/ca.crt differs from ca.crt"
[ ! -e "$tmp/public/ca.key" ] || fail "private key leaked into public/"
# Брокеру монтируется только out/broker: ключа CA там быть не должно
[ "$(cd "$tmp/broker" && ls | sort | tr '\n' ' ')" = "ca.crt server.crt server.key " ] || fail "broker/ must contain exactly ca.crt server.crt server.key"
[ "$(stat -f '%Lp' "$tmp/server.key" 2>/dev/null || stat -c '%a' "$tmp/server.key")" = "600" ] || fail "server.key permissions are not 600"
cmp -s "$tmp/server.crt" "$tmp/broker/server.crt" || fail "broker/server.crt is stale"

ca_before="$(openssl x509 -in "$tmp/ca.crt" -noout -fingerprint -sha256)"
srv_before="$(openssl x509 -in "$tmp/server.crt" -noout -serial)"
OUT_DIR="$tmp" "$here/make-ca.sh" 192.168.50.7 >/dev/null
[ "$ca_before" = "$(openssl x509 -in "$tmp/ca.crt" -noout -fingerprint -sha256)" ] || fail "CA was re-issued on second run"
[ "$srv_before" != "$(openssl x509 -in "$tmp/server.crt" -noout -serial)" ] || fail "server cert was not re-issued"
openssl verify -CAfile "$tmp/ca.crt" "$tmp/server.crt" | grep -q ': OK$' || fail "re-issued server.crt not signed by the same CA"

echo "make-ca.sh: all checks passed"
