#!/usr/bin/env bash
# Провижининг DynSec для dev-стенда: роли device и backend-service (общий файл dynsec-roles.sh), operator-dev;
# клиенты dev-emu-26..37, dev-do-26, dev-do-36, dev-acl-test (только для test-dynsec-dev.sh, агентам не выдаётся),
# operator и backend (учётка бэкенда, clientId = backend, роль backend-service; реш. 230).
# Идемпотентен: роли и ACL применяются заново, существующие клиенты пропускаются (пароль не меняется).
# Пароли новых клиентов печатаются ОДИН раз — в deploy/out/dev-credentials.txt (600): "<username> <password>" на строку;
# пароль backend — отдельным файлом deploy/out/backend-mqtt-password (600, одна строка): его монтирует контейнер бэкенда.
# Команды уходят в контейнер через stdin; пароли — только в stdin и в файлах опций 600 во временном каталоге контейнера
# (mosquitto/broker-opts.sh, реш. 308), в argv процессов ни хоста, ни контейнера они не попадают.
set -euo pipefail
cd "$(dirname "$0")/.."
. mosquitto/broker-opts.sh

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f .env ] || fail "нет deploy/.env (см. deploy/README.md)"
set -a; . ./.env; set +a
: "${DYNSEC_ADMIN_PASSWORD:?DYNSEC_ADMIN_PASSWORD is not set (deploy/.env)}"

creds=out/dev-credentials.txt
backend_pw_file=out/backend-mqtt-password
clients=()
for api in 26 27 28 29 30 31 32 33 34 35 36 37; do clients+=("dev-emu-$api"); done
# dev-acl-test — субъект test-dynsec-dev.sh: проверка не вытесняет работающих агентов (решение 178)
clients+=(dev-do-26 dev-do-36 dev-acl-test)

# Выполняет stdin как sh-скрипт в контейнере брокера; ctrl — mosquitto_ctrl под admin (учётка — в файле опций)
in_broker() {
  {
    broker_opts_begin
    broker_opts_ctrl "$DYNSEC_ADMIN_PASSWORD"
    cat
  } | docker compose exec -T mosquitto sh -s
}

existing="$(echo 'ctrl dynsec listClients' | in_broker)" || fail "не удалось получить список клиентов"
grep -qx admin <<<"$existing" || fail "брокер не отвечает как admin (listClients): $(head -c 200 <<<"$existing")"
has_client() { grep -qx -- "$1" <<<"$existing"; }

umask 077
mkdir -p out
chmod 700 out
touch "$creds"
chmod 600 "$creds"
# Пароль backend: новый — только если клиента ещё нет (иначе файл не трогаем). Как и dev-учётки, файл пишется до
# обращения к брокеру: если создание оборвётся, следующий запуск не найдёт клиента и перезапишет пароль.
new_backend_pw=""
if ! has_client backend; then
  new_backend_pw="$(openssl rand -base64 32)"
  printf '%s\n' "$new_backend_pw" >"$backend_pw_file"
  chmod 600 "$backend_pw_file"
fi

# Новые клиенты: пароль генерируется на хосте и сразу записывается в файл учёток
new_clients=()
for name in "${clients[@]}" operator; do
  has_client "$name" && continue
  new_clients+=("$name")
done
script="$(mktemp)"
tmp_new="$(mktemp)"
trap 'rm -f "$script" "$tmp_new"' EXIT
chmod 600 "$script" "$tmp_new"

{
  cat <<'SH'
set -eu
# mosquitto_ctrl возвращает 0 даже при ошибке команды, поэтому смотрим на текст ответа
run() {
  out="$(ctrl dynsec "$@" 2>&1)" || { echo "FAIL: dynsec $1 $2: $out"; exit 1; }
  case "$out" in *rror*) echo "FAIL: dynsec $1 $2: $out"; exit 1 ;; esac
}
try() { ctrl dynsec "$@" >/dev/null 2>&1 || true; }
has_role() { ctrl dynsec listRoles 2>&1 | grep -qx "$1"; }
SH
  # Роли продукта (device, backend-service) и запрет по умолчанию — общий файл, его же применяют тесты server:data
  cat mosquitto/dynsec-roles.sh
  echo 'has_role operator-dev || run createRole operator-dev'
  for acl in publishClientSend publishClientReceive subscribePattern; do
    echo "try removeRoleACL operator-dev $acl rf/v1/#"
    echo "run addRoleACL operator-dev $acl rf/v1/# allow"
  done

  for name in ${new_clients[@]+"${new_clients[@]}"}; do
    pw="$(openssl rand -base64 32)"
    printf '%s %s\n' "$name" "$pw" >>"$tmp_new"
    if [ "$name" = operator ]; then
      # clientId не фиксируется: одновременно нужны mosquitto_sub и mosquitto_pub
      broker_create_client operator "$pw"
      echo "run addClientRole operator operator-dev"
    else
      broker_create_client "$name" "$pw"
      echo "run setClientId $name $name"
      echo "run addClientRole $name device"
    fi
  done
  if [ -n "$new_backend_pw" ]; then
    broker_create_client backend "$new_backend_pw" -i backend
    echo "run addClientRole backend backend-service"
  fi
  # Файл учёток — источник истины: если прошлый запуск оборвался, старую строку нового клиента заменяем
  if [ -s "$tmp_new" ]; then
    keep="$(mktemp)"
    chmod 600 "$keep"
    awk 'NR==FNR { drop[$1]=1; next } !($1 in drop)' "$tmp_new" "$creds" >"$keep"
    cat "$tmp_new" >>"$keep"
    mv "$keep" "$creds"
  fi
} >"$script"

in_broker <"$script" || fail "провижининг DynSec не завершился (см. вывод выше)"

new_backend_pw=""
has_client backend && [ ! -s "$backend_pw_file" ] \
  && echo "WARN: клиент backend уже есть в брокере, но нет $backend_pw_file (пароль не менялся)" >&2

for name in "${clients[@]}" operator; do
  has_client "$name" && [ -z "$(awk -v n="$name" '$1==n' "$creds")" ] \
    && echo "WARN: клиент $name уже есть в брокере, но его пароля нет в $creds (пароль не менялся)" >&2
done

echo "DynSec: роли device, backend-service, operator-dev готовы; backend: deploy/$backend_pw_file; новых клиентов: ${#new_clients[@]}; учётки: deploy/$creds ($(wc -l <"$creds" | tr -d ' ') строк)"
