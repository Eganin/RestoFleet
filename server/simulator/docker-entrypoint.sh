#!/bin/sh
# Точка входа контейнера симулятора. Если задан RF_SIM_NETEM (например `loss 5%`), на интерфейсе, через который
# контейнер ходит к RF_SIM_NETEM_HOST (по умолчанию mosquitto), включается `tc netem` — деградация сети устройств
# (план 4.3). Интерфейс измерителя (сеть control) не трогается. Затем процесс переходит на uid 10001 без capabilities.
set -eu

if [ -n "${RF_SIM_NETEM:-}" ]; then
  case "$RF_SIM_NETEM" in
    *[!a-z0-9.%\ ]*) echo "RF_SIM_NETEM: only netem words, numbers and % allowed" >&2; exit 2 ;;
  esac
  host="${RF_SIM_NETEM_HOST:-mosquitto}"
  addr="$(getent hosts "$host" | awk '{print $1; exit}')"
  [ -n "$addr" ] || { echo "RF_SIM_NETEM_HOST $host does not resolve" >&2; exit 2; }
  dev="$(ip -o route get "$addr" | sed -n 's/.* dev \([^ ]*\).*/\1/p')"
  # shellcheck disable=SC2086 # параметры netem — список слов
  tc qdisc add dev "$dev" root netem $RF_SIM_NETEM
  echo "netem on $dev (route to $host): $(tc qdisc show dev "$dev")"
fi

exec setpriv --reuid=10001 --regid=10001 --clear-groups --inh-caps=-all /opt/simulator/bin/restofleet-simulator "$@"
