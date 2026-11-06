#!/usr/bin/env bash
# Временные нарушения архитектуры для проверки правил Konsist: konsist-violations.sh add|remove
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
files=(
  "server/domain/src/main/kotlin/ru/restofleet/server/domain/ViolationA.kt"
  "agent/domain/src/commonMain/kotlin/ru/restofleet/agent/domain/ViolationB.kt"
  "agent/presentation/src/main/kotlin/ru/restofleet/agent/presentation/ViolationC.kt"
  "agent/presentation/src/main/kotlin/ru/restofleet/agent/presentation/ViolationD.kt"
)
case "$1" in
  add)
    mkdir -p "$root/$(dirname "${files[0]}")" "$root/$(dirname "${files[1]}")" "$root/$(dirname "${files[2]}")"
    printf 'package ru.restofleet.server.domain\n\nimport android.content.Context\n\nclass ViolationA\n' >"$root/${files[0]}"
    printf 'package ru.restofleet.agent.domain\n\nimport ru.restofleet.shared.protocol.dto.TelemetryDto\n\nclass ViolationB\n' >"$root/${files[1]}"
    printf 'package ru.restofleet.agent.presentation\n\nimport org.koin.android.ext.android.inject\n\nclass ViolationC\n' >"$root/${files[2]}"
    printf 'package ru.restofleet.agent.presentation\n\nimport ru.restofleet.agent.data.outbox.RoomOutbox\n\nclass LabReducer\n' >"$root/${files[3]}"
    ;;
  remove)
    for f in "${files[@]}"; do rm -f "$root/$f"; done
    for d in server/domain agent/domain agent/presentation server agent; do
      [ -d "$root/$d" ] && find "$root/$d" -type d -empty -delete 2>/dev/null || true
    done
    ;;
esac
