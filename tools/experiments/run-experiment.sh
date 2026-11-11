#!/usr/bin/env bash
# Эксперимент «MQTT vs REST-polling» (КМ4, план 4.3): оркестратор прогонов на стенде deploy/.
#
#   tools/experiments/run-experiment.sh build              # installDist + образ restofleet/simulator:dev
#   tools/experiments/run-experiment.sh register 5000      # дорегистрировать устройства до N (кэш deploy/out/sim, 600)
#   tools/experiments/run-experiment.sh run <сценарий>     # один прогон: tools/experiments/scenarios/<сценарий>.yaml
#   tools/experiments/run-experiment.sh restore            # бэкенд с настройками по умолчанию, Toxiproxy — reset
#
# Условия прогона определяются именем сценария (реш. 214, 284):
#   pollIntervalSec: P в сценарии → RF_POLLING_STALEAFTERMS = 3 × P с (presence REST); иначе 90 000 (по умолчанию);
#   …-lat300   → Toxiproxy: +300 мс (latency, downstream) на прокси mqtt и rest, после прогона снимается;
#   …-loss5    → tc netem loss 5 % на интерфейсе устройств в контейнере симулятора (RF_SIM_NETEM).
# Бэкенд меняется только через `up -d --no-deps backend` с переменными окружения: deploy/.env не правится.
# Результаты: logs/experiments/<сценарий>/ (вне git) — CSV симулятора, docker-stats.csv (сэмплер, 5 с), журнал.
set -euo pipefail
cd "$(dirname "$0")/../.."

compose() { docker compose -f deploy/docker-compose.yml "$@"; }
toxi() { curl -fsS -X "$1" "http://127.0.0.1:8474$2" ${3:+-d "$3"} >/dev/null; }
log() { printf '== %s %s\n' "$(date '+%H:%M:%S')" "$*"; }

prepare_dirs() {
  umask 077
  mkdir -p deploy/out/sim logs/experiments
  chmod 700 deploy/out/sim
}

backend_env() {
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' restofleet-backend-1 | sed -n "s/^$1=//p"
}

# Бэкенд с заданными RF_* (остальные — по умолчанию compose); пересоздаётся, только если значения отличаются
backend_with() {
  local stale=$1 provision=$2
  local networks
  networks=$(docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' restofleet-backend-1)
  if [ "$(backend_env RF_POLLING_STALEAFTERMS)" != "$stale" ] ||
    [ "$(backend_env RF_RATELIMIT_PROVISIONPERMINUTE)" != "$provision" ] || [[ $networks != *restofleet_control* ]]; then
    log "backend: RF_POLLING_STALEAFTERMS=$stale RF_RATELIMIT_PROVISIONPERMINUTE=$provision"
    RF_POLLING_STALEAFTERMS=$stale RF_RATELIMIT_PROVISIONPERMINUTE=$provision \
      compose up -d --wait --no-deps backend >/dev/null
  fi
}

conditions() {
  {
    echo "date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "git: $(git rev-parse --short HEAD)$(git diff --quiet || echo '+dirty')"
    echo "host: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), cpus $(sysctl -n hw.ncpu 2>/dev/null), mem $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 )) GiB"
    echo "docker: $(docker info --format '{{.ServerVersion}}, VM cpus {{.NCPU}}, mem {{.MemTotal}}')"
    echo "simulator: cpus ${RF_SIM_CPUS:-2}, mem ${RF_SIM_MEM:-3g}, heap ${RF_SIM_HEAP:-2g}"
    echo "$@"
  }
}

cmd=${1:-}
case "$cmd" in
  build)
    ./gradlew -q :server:simulator:installDist
    compose --profile experiment build -q simulator
    log "образ restofleet/simulator:dev собран"
    ;;
  register)
    count=${2:?нужно число устройств}
    prepare_dirs
    backend_with 90000 100000
    log "register: до $count устройств"
    compose --profile experiment run --rm --no-deps -T simulator \
      register --scenario /scenarios/mqtt-5000.yaml --devices "$count" --parallel "${PARALLEL:-8}" \
      2>&1 | tee -a logs/experiments/registration.log
    backend_with 90000 60
    ;;
  run)
    name=${2:?нужно имя сценария}
    [ -f "tools/experiments/scenarios/$name.yaml" ] || { echo "нет сценария $name" >&2; exit 2; }
    prepare_dirs
    out=logs/experiments/$name
    rm -rf "$out"
    mkdir -p "$out"
    stale=90000
    poll=$(sed -n 's/^pollIntervalSec: *\([0-9][0-9]*\).*/\1/p' "tools/experiments/scenarios/$name.yaml")
    if [ -n "$poll" ]; then stale=$((poll * 3000)); fi
    backend_with "$stale" 60
    netem=""
    netem_host=mosquitto
    toxics=""
    if [[ $name == *-loss5 ]]; then
      netem="loss 5%"
      [[ $name == rest* ]] && netem_host=backend-devices
    fi
    sampler=""
    cleanup() {
      [ -n "$sampler" ] && kill "$sampler" 2>/dev/null && wait "$sampler" 2>/dev/null
      for proxy in $toxics; do toxi DELETE "/proxies/$proxy/toxics/lat300" || true; done
    }
    trap cleanup EXIT
    if [[ $name == *-lat300 ]]; then
      for proxy in mqtt rest; do
        toxi POST "/proxies/$proxy/toxics" \
          '{"name":"lat300","type":"latency","stream":"downstream","attributes":{"latency":300}}'
        toxics="$toxics $proxy"
      done
    fi
    conditions "scenario: $name" "polling stale: $stale ms" "netem: ${netem:-none} (host $netem_host)" \
      "toxics: ${toxics:-none}" >"$out/conditions.txt"
    python3 -I tools/experiments/sample_docker_stats.py "$out/docker-stats.csv" &
    sampler=$!
    log "run $name (stale=$stale netem='${netem}' toxics='${toxics# }')"
    RF_SIM_NETEM=$netem RF_SIM_NETEM_HOST=$netem_host compose --profile experiment run --rm --no-deps -T simulator \
      experiment --scenario "/scenarios/$name.yaml" 2>&1 | tee "$out/simulator.log"
    log "done $name"
    ;;
  restore)
    backend_with 90000 60
    toxi POST /reset
    log "стенд: бэкенд по умолчанию, Toxiproxy reset"
    ;;
  *)
    sed -n '2,16p' "$0" >&2
    exit 2
    ;;
esac
