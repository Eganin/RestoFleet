#!/usr/bin/env bash
# Создаёт AVD матрицы MVP-A (docs/stages/0-mvp-feasibility.md). Создаётся ТОЛЬКО то, что названо в аргументах:
# AVD весят 6-8 ГБ каждый, поэтому по умолчанию скрипт ничего не делает, а печатает таблицу.
#
#   create-avds.sh list                 таблица AVD и образов
#   create-avds.sh create rf-api26 ...  скачать образ (sdkmanager) и создать AVD
#   create-avds.sh delete rf-api26 ...  удалить AVD (образ остаётся)
#
# Параметры: pixel_6, 4 ГБ RAM, без Play Store; DO-AVD — pixel_tablet без аккаунтов; rf-api36-qr (D7) — на образе
# google_apis_playstore. Образ 37.2 в репозитории Google отсутствует (на 2026-11-10 есть 37.0): Ruling, rf-api37 на 37.0.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
ABI=arm64-v8a
MIN_FREE_GB=15

# имя | API для platforms/system-images | вариант образа | устройство
TABLE=(
  "rf-api26|26|google_apis|pixel_6"
  "rf-api29|29|google_apis|pixel_6"
  "rf-api31|31|google_apis|pixel_6"
  "rf-api33|33|google_apis|pixel_6"
  "rf-api35|35|google_apis|pixel_6"
  "rf-api36|36|google_apis|pixel_6"
  "rf-api37|37.0|google_apis|pixel_6"
  "rf-api26-do|26|google_apis|pixel_tablet"
  "rf-api35-do|35|google_apis|pixel_tablet"
  "rf-api36-do|36|google_apis|pixel_tablet"
  "rf-api37-do|37.0|google_apis|pixel_tablet"
  "rf-api36-qr|36|google_apis_playstore|pixel_6"
)

row() { local r; for r in "${TABLE[@]}"; do [[ "${r%%|*}" == "$1" ]] && { echo "$r"; return 0; }; done; return 1; }

free_gb() { df -g "$HOME" | awk 'NR==2 {print $4}'; }

create_one() {
  local name="$1" r api variant device image
  r="$(row "$name")" || { echo "неизвестный AVD: $name" >&2; return 1; }
  IFS='|' read -r _ api variant device <<<"$r"
  if (( $(free_gb) < MIN_FREE_GB )); then
    echo "свободно < ${MIN_FREE_GB} ГБ, стоп" >&2; return 1
  fi
  image="system-images;android-${api};${variant};${ABI}"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" "platforms;android-${api}" "$image"
  echo no | "$AVDMANAGER" create avd --force -n "$name" -k "$image" -d "$device"
  local cfg="$AVD_HOME/$name.avd/config.ini"
  # 4 ГБ RAM; без Play Store (кроме rf-api36-qr)
  sed -i.bak -e '/^hw.ramSize/d' -e '/^PlayStore.enabled/d' "$cfg" && rm -f "$cfg.bak"
  { echo "hw.ramSize=4096"; [[ "$variant" == *playstore ]] && echo "PlayStore.enabled=yes" || echo "PlayStore.enabled=no"; } >>"$cfg"
  echo "создан $name ($image, $device)"
}

case "${1:-list}" in
  list)
    printf '%-14s %-6s %-22s %s\n' AVD API IMAGE DEVICE
    for r in "${TABLE[@]}"; do IFS='|' read -r n a v d <<<"$r"; printf '%-14s %-6s %-22s %s\n' "$n" "$a" "$v" "$d"; done ;;
  create) shift; for n in "$@"; do create_one "$n"; done ;;
  delete) shift; for n in "$@"; do "$AVDMANAGER" delete avd -n "$n"; done ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
