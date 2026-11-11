# Файлы опций mosquitto_ctrl / mosquitto_pub / mosquitto_sub внутри контейнера брокера (R-10, реш. 308).
# Не исполняется сам: подключается (`. mosquitto/broker-opts.sh`) в bash-скрипты стенда. Функции печатают фрагменты
# sh-скрипта, который скрипт стенда отправляет в контейнер через stdin (`docker compose exec -T mosquitto sh -s`):
# пароль оказывается только в stdin и в файле 600 во временном каталоге контейнера (XDG_CONFIG_HOME), но не в argv
# процессов ни на хосте (printf — встроенная команда bash), ни в контейнере. Каталог удаляется при выходе скрипта.

# Начало скрипта: временный каталог $rf_opts (700) и его удаление при выходе
broker_opts_begin() {
  cat <<'SH'
umask 077
rf_opts="$(mktemp -d)"
trap 'rm -rf "$rf_opts"' EXIT
SH
}

# broker_opts_ctrl <пароль admin>: файл опций mosquitto_ctrl (admin DynSec по TLS) и функция ctrl
broker_opts_ctrl() {
  printf 'cat >"$rf_opts/mosquitto_ctrl" <<'"'"'RF_OPTS'"'"'\n-h localhost\n-p 8883\n--cafile /mosquitto/certs/ca.crt\n-u admin\n-P %s\nRF_OPTS\n' "$1"
  echo 'ctrl() { XDG_CONFIG_HOME="$rf_opts" mosquitto_ctrl "$@"; }'
}

# broker_opts_client <имя функции> <username> <пароль>: файлы опций mosquitto_pub и mosquitto_sub (TLS, MQTT 5) этой
# учётки и функция-обёртка: `<имя> mosquitto_pub -i <clientId> …`. clientId в файл не пишется — его задаёт вызов.
broker_opts_client() {
  local fn="$1" user="$2" password="$3" tool
  printf 'mkdir -p "$rf_opts/%s"\n' "$fn"
  for tool in mosquitto_pub mosquitto_sub; do
    printf 'cat >"$rf_opts/%s/%s" <<'"'"'RF_OPTS'"'"'\n-h localhost\n-p 8883\n--cafile /mosquitto/certs/ca.crt\n-V mqttv5\n-u %s\n-P %s\nRF_OPTS\n' \
      "$fn" "$tool" "$user" "$password"
  done
  printf '%s() { XDG_CONFIG_HOME="$rf_opts/%s" "$@"; }\n' "$fn" "$fn"
}

# broker_create_client <username> <пароль> [аргументы createClient…]: строка `run createClient …`, пароль — через
# stdin mosquitto_ctrl (heredoc; без -p он спрашивает пароль дважды)
broker_create_client() {
  local user="$1" password="$2"
  shift 2
  printf 'run createClient %s %s <<'"'"'RF_PW'"'"'\n%s\n%s\nRF_PW\n' "$user" "$*" "$password" "$password"
}
