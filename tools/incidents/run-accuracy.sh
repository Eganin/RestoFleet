#!/usr/bin/env bash
# Сценарии S8 и точность классификатора (ПМ4, реш. 428) на стенде deploy/.
#
#   tools/incidents/run-accuracy.sh build          # installDist + образ restofleet/simulator:dev
#   tools/incidents/run-accuracy.sh prepare        # рестораны S8-01…S8-12 и 72 устройства (кэш deploy/out/sim/creds-s8.jsonl)
#   tools/incidents/run-accuracy.sh run <сценарий> # прогон tools/incidents/scenarios/<сценарий>.yaml и оценка
#   tools/incidents/run-accuracy.sh eval <truth.csv> [отчёт.md]  # оценка готового эталона по GET /incidents и /alerts
#
# Эталон пишет симулятор: deploy/out/sim/truth-<сценарий>-<старт>.csv (600). Результат: accuracy → 
# docs/experiments/incidents/accuracy.md, остальные сценарии → docs/experiments/incidents/<сценарий>.md.
# Пароль admin читается из deploy/out/admin-password в файл запроса (600), в argv и вывод не попадает.
set -euo pipefail
cd "$(dirname "$0")/../.."

compose() { docker compose -f deploy/docker-compose.yml "$@"; }
log() { printf '== %s %s\n' "$(date '+%H:%M:%S')" "$*"; }
BASE=${RF_BASE:-https://localhost:8443}
CA=deploy/certs/out/public/ca.crt
CREDS=/var/lib/simulator/creds-s8.jsonl

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
umask 077

login() {
  python3 -I -c 'import json,sys; print(json.dumps({"login":"admin","password":open(sys.argv[1]).read().strip()}))' \
    deploy/out/admin-password >"$tmp/login.json"
  curl -fsS --cacert "$CA" -H 'Content-Type: application/json' --data-binary "@$tmp/login.json" \
    "$BASE/api/v1/auth/login" >"$tmp/tokens.json"
  python3 -I -c 'import json,sys; print("Authorization: Bearer " + json.load(open(sys.argv[1]))["accessToken"])' \
    "$tmp/tokens.json" >"$tmp/auth-header"
}

fetch() { curl -fsS --cacert "$CA" -H "@$tmp/auth-header" "$BASE$1"; }

evaluate() {
  local truth=$1 out=$2 since
  since=$(python3 -I -c 'import csv,sys; r=list(csv.DictReader(open(sys.argv[1]))); print(min(int(x["from"]) for x in r) - 600000)' "$truth")
  login
  fetch "/api/v1/incidents?from=$since&limit=1000" >"$tmp/incidents.json"
  fetch "/api/v1/alerts?rule=R-01&includeSuppressed=true&limit=1000" >"$tmp/alerts.json"
  python3 -I tools/incidents/accuracy.py --truth "$truth" --incidents "$tmp/incidents.json" \
    --alerts "$tmp/alerts.json" --out "$out" --title "Точность классификатора: $(basename "$truth" .csv)"
}

cmd=${1:-}
case "$cmd" in
  build)
    ./gradlew -q :server:simulator:installDist
    compose --profile experiment build -q simulator
    ;;
  prepare)
    mkdir -p deploy/out/sim && chmod 700 deploy/out/sim
    compose --profile experiment run --rm simulator prepare --scenario /incident-scenarios/s8-a.yaml --creds "$CREDS"
    ;;
  run)
    name=${2:?сценарий: s8-a | s8-b | s8-c | accuracy | sm04}
    [ -f "tools/incidents/scenarios/$name.yaml" ] || { echo "нет tools/incidents/scenarios/$name.yaml" >&2; exit 2; }
    log "run $name"
    compose --profile experiment run --rm simulator run --scenario "/incident-scenarios/$name.yaml" --creds "$CREDS"
    truth=$(ls -t deploy/out/sim/truth-"$name"-*.csv 2>/dev/null | head -1 || true)
    if [ -z "$truth" ]; then log "эталона нет (сценарий без отказов сети и бэкенда)"; exit 0; fi
    mkdir -p docs/experiments/incidents
    out="docs/experiments/incidents/$name.md"
    [ "$name" = accuracy ] && out=docs/experiments/incidents/accuracy.md
    # Закрытие последнего эпизода: два тика без условия и пробы K — до 2 мин после конца прогона
    sleep "${RF_SETTLE_SEC:-90}"
    evaluate "$truth" "$out"
    log "отчёт: $out"
    ;;
  eval)
    evaluate "${2:?truth.csv}" "${3:-docs/experiments/incidents/eval.md}"
    ;;
  *)
    sed -n '2,11p' "$0"
    exit 2
    ;;
esac
