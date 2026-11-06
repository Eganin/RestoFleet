#!/bin/sh
# Однократная инициализация Dynamic Security: создаёт admin, если файла ещё нет.
set -eu
file=/mosquitto/data/dynamic-security.json
: "${DYNSEC_ADMIN_PASSWORD:?DYNSEC_ADMIN_PASSWORD is not set (deploy/.env)}"
if [ -s "$file" ]; then
  echo "DynSec уже инициализирован: $file"
else
  mosquitto_ctrl dynsec init "$file" admin "$DYNSEC_ADMIN_PASSWORD"
  echo "DynSec инициализирован, admin создан"
fi
chown -R mosquitto:mosquitto /mosquitto/data
chmod 600 "$file"
