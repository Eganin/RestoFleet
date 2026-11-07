#!/usr/bin/env bash
# Собирает tools:dummy-waiter версии 4.N и кладёт APK и sha256 в deploy/apk/ (каталог в .gitignore).
# Использование: tools/dev/build-dummy-apk.sh <versionCode>   например: 13, 14
set -euo pipefail

n="${1:?usage: tools/dev/build-dummy-apk.sh <versionCode>}"
[[ "$n" =~ ^[0-9]+$ ]] || { echo "versionCode must be a number: $n" >&2; exit 2; }

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

./gradlew :tools:dummy-waiter:assembleDebug "-PdummyVersionCode=$n"

mkdir -p deploy/apk
name="waiter-4.$n.apk"
cp tools/dummy-waiter/build/outputs/apk/debug/dummy-waiter-debug.apk "deploy/apk/$name"
if command -v sha256sum >/dev/null; then sum=(sha256sum); else sum=(shasum -a 256); fi
(cd deploy/apk && "${sum[@]}" "$name" > "$name.sha256")
cat "deploy/apk/$name.sha256"
