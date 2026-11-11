#!/bin/sh
# Однократная инициализация Dynamic Security: создаёт admin, если файла ещё нет.
# Роли (device, backend-service) и учётку backend здесь не создать: офлайн mosquitto_ctrl умеет только `dynsec init` и
# `setClientPassword` (реш. 231). Их создаёт mosquitto/dynsec-dev.sh на работающем брокере (роли — dynsec-roles.sh).
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
