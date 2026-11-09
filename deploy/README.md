# Стенд RestoFleet (MVP)

Mosquitto 2.1.2: только TLS на `8883`, анонимы запрещены, учётки — Dynamic Security, persistence на томе `restofleet_mosquitto-data`. APK-хост (nginx 1.30.6): HTTPS на `8443`.

## Запуск (из каталога `deploy/`)

```bash
cp .env.example .env && sed -i '' "s|change-me|$(openssl rand -base64 32 | tr -d '/+=')|" .env   # один раз
./certs/make-ca.sh                                        # CA (один раз) + серверный сертификат; LAN-IP — аргументом
docker compose --profile init run --rm dynsec-init        # один раз: создаёт admin в dynamic-security.json
docker compose up -d --wait                               # брокер и apk-host, ждёт healthy
../tools/dev/build-dummy-apk.sh 13                        # один раз: APK для проверок APK-хоста (см. ниже)
./test-stand.sh                                           # smoke: TLS, отказ анонимам и чужому паролю, нет 1883, нет ca.key и пароля в контейнере, APK-хост, persistence
```

Проверка скрипта сертификатов: `./certs/test-make-ca.sh`.

## Что где лежит

| Путь | Что | В git |
|---|---|---|
| `certs/make-ca.sh` | CA стенда (EC P-256, 10 лет, не перевыпускается) и серверный сертификат (825 дней, перевыпускается при каждом запуске) | да |
| `certs/out/ca.key` | **ключ CA**, права 600 | **нет** |
| `certs/out/server.{key,crt}` | сертификат брокера; ключ — права 600 | нет |
| `certs/out/broker/` | **единственное, что монтируется в брокер**: `ca.crt`, `server.crt`, `server.key`. Ключа CA там нет; при старте контейнер копирует ключ себе с владельцем `mosquitto` и правами 600 | нет |
| `certs/out/public/ca.crt` | единственный файл для клиентов: агент (assets), MQTT Explorer, `mosquitto_*` | нет |
| `nginx/apk-host.conf` | конфигурация APK-хоста | да |
| `apk/` | `waiter-4.N.apk` и `waiter-4.N.apk.sha256`, собирает `tools/dev/build-dummy-apk.sh` | **нет** |
| `mosquitto/mosquitto.conf` | конфигурация брокера | да |
| `mosquitto/init-dynsec.sh` | однократная инициализация DynSec | да |
| `mosquitto/dynsec-dev.sh` | роли `device`, `operator-dev` и dev-клиенты (идемпотентно) | да |
| `mosquitto/test-dynsec-dev.sh` | проверка ACL и идемпотентности на живом стенде | да |
| `out/dev-credentials.txt` | пароли dev-клиентов, `<username> <password>`, права 600 | **нет** |
| `.env` | `DYNSEC_ADMIN_PASSWORD` | **нет** |

SAN серверного сертификата: `localhost`, `mosquitto`, `apk-host`, `127.0.0.1`, `10.0.2.2` (хост из эмулятора), IP машины в LAN (реальные устройства, MVP-B). После смены сети машины — перезапустить `./certs/make-ca.sh <новый IP>` и `docker compose restart mosquitto`; CA при этом не меняется, агент перевыпуска не требует.

## APK-хост

Раздаёт тестовое приложение `tools:dummy-waiter` (`ru.restofleet.dummy.waiter`) для D3 (тихая установка) и R7/R12 (события пакетов).

```bash
tools/dev/build-dummy-apk.sh 13     # из корня репозитория: deploy/apk/waiter-4.13.apk + .sha256 (14 — для проверки обновления)
cd deploy && docker compose up -d --wait apk-host
curl --cacert certs/out/ca.crt https://localhost:8443/waiter-4.13.apk -o /tmp/x
```

Адреса: с хоста `https://localhost:8443/waiter-4.13.apk`, из эмулятора `https://10.0.2.2:8443/waiter-4.13.apk`, с реального устройства `https://<LAN-IP>:8443/...` (IP должен быть в SAN, см. выше). Рядом отдаётся `waiter-4.13.apk.sha256` в формате `sha256sum`.

Ограничения хоста: только GET/HEAD (остальное 403), только `*.apk` и `*.sha256`, всё прочее и листинг каталога — 404, TLS 1.2+, порта 80 нет, `deploy/apk` смонтирован read-only, контейнер `read_only` и без лишних capabilities. В контейнер монтируется только `certs/out/broker/` (ключа CA там нет); ключ сервера с правами 600 читает master-процесс nginx (root) через `DAC_READ_SEARCH`, worker-процессы его не видят.

Агент должен доверять CA стенда **только в debug-сборке**: через `network_security_config` с `<debug-overrides>` (делают задачи 2.3/2.5, не этот стенд). В release-сборке CA стенда не доверяется.

## Подключение вручную

```bash
docker compose exec mosquitto mosquitto_sub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt \
  -u admin -P "$DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1
```

**`admin` после `dynsec init` имеет полный доступ**: управляет DynSec (`$CONTROL/dynamic-security/#`) и может читать все топики (`subscribePattern #`). Поэтому его пароль хранится только в `deploy/.env` и передаётся лишь одноразовому `dynsec-init` — у брокера его нет ни в окружении, ни в healthcheck (healthcheck проверяет, что анонимный клиент получает CONNACK «not authorised»). Роли `device` и `operator-dev` и учётки устройств создаёт `mosquitto/dynsec-dev.sh` (см. ниже).

## Учётки устройств (DynSec)

```bash
mosquitto/dynsec-dev.sh        # из deploy/, после `docker compose up -d --wait`
mosquitto/test-dynsec-dev.sh   # проверка ACL: PASS/FAIL, код возврата != 0 при провале
```

Скрипт создаёт роль `device` (по умолчанию всё запрещено): публикация в `rf/v1/dev/%u/{status,telemetry,probe,events,shadow/reported,cmd/res}`, подписка и получение только `rf/v1/dev/%u/shadow/desired` и `rf/v1/dev/%u/cmd/req`. `%u` — имя клиента, оно же `clientId` (`setClientId`), поэтому устройство не может ни подключиться под чужим `clientId`, ни писать в чужие топики. Клиенты: `dev-emu-26` … `dev-emu-37` (эмуляторы по уровню API), `dev-do-26`, `dev-do-36` (эмуляторы с Device Owner), `dev-acl-test` (роль `device`, только субъект `test-dynsec-dev.sh`: проверка не вытесняет работающих агентов), `operator` (роль `operator-dev`: всё на `rf/v1/#`, `clientId` не фиксирован). `test-dynsec-dev.sh` подключает оператора с `clientId` `acl-test-op-*`, поэтому живую подписку `op-sub` не трогает; перед первым прогоном после обновления выполните `mosquitto/dynsec-dev.sh`, он добавит `dev-acl-test`.

- **Пароли** (`openssl rand -base64 32`) выводятся один раз: в `deploy/out/dev-credentials.txt` (права 600, в git не попадает), по строке `<username> <password>`. В терминал и логи скрипт их не печатает. Агенту/эмулятору пароль берётся оттуда: `awk '$1=="dev-emu-36" {print $2}' out/dev-credentials.txt`.
- **Повторный запуск** безопасен: ACL применяются заново, существующие клиенты пропускаются (пароль не меняется, файл не дописывается).
- **Добавить клиента:** внести имя в массив `clients` в `mosquitto/dynsec-dev.sh` и запустить скрипт; пароль появится в файле учёток. Потерянный пароль: `mosquitto_ctrl … dynsec deleteClient <имя>`, затем скрипт.
- **Подписка оператором на всё** (замена MQTT Explorer, без окон). Пароль не должен попадать в argv процессов, поэтому опции с ним записываются в файл внутри контейнера через stdin (`mosquitto_sub`/`mosquitto_pub` читают `$XDG_CONFIG_HOME/<имя утилиты>`):

```bash
PW="$(awk '$1=="operator" {print $2}' out/dev-credentials.txt)"
printf '%s\n' '-u operator' "-P $PW" '--cafile /mosquitto/certs/ca.crt' \
  | docker compose exec -T mosquitto sh -c 'umask 077; mkdir -p /tmp/rfopt; cat > /tmp/rfopt/mosquitto_sub'
unset PW
docker compose exec -T -e XDG_CONFIG_HOME=/tmp/rfopt mosquitto \
  mosquitto_sub -h localhost -p 8883 -V mqttv5 -i op-sub -t 'rf/v1/#' -v -F '%I %t %p'
docker compose exec -T mosquitto rm -rf /tmp/rfopt          # опции удаляются после проверки
```

Публикация команды: тот же приём с файлом `/tmp/rfopt/mosquitto_pub` и вызовом `mosquitto_pub -i op-pub -q 1 -t rf/v1/dev/<id>/cmd/req -m '…'`. На хосте пароль живёт только в переменной оболочки (`printf` встроен в оболочку и в argv не попадает), в контейнере — в файле с правами 600 на время проверки.

## Сброс стенда

```bash
docker compose down -v      # удаляет том: DynSec-учётки и persistence пропадут
```

## Проверки MVP

Цепочка M1–M7, P4, RS1–RS2 на эмуляторе `rf-api36` (результаты — в `docs/stages/0-mvp-feasibility.md`). Пароли в аргументы не попадают: опции оператора лежат в файлах контейнера, учётка агента передаётся через stdin оболочки устройства.

```bash
# опции оператора внутри контейнера (пароль читается из out/dev-credentials.txt, не печатается)
cd deploy; PW=$(awk '$1=="operator"{print $2}' out/dev-credentials.txt)
{ printf 'umask 077\nmkdir -p /tmp/rfopt\n'; for t in mosquitto_sub mosquitto_pub; do
  printf "cat > /tmp/rfopt/%s <<'EOF'\n-h localhost\n-p 8883\n-V mqttv5\n--cafile /mosquitto/certs/ca.crt\n-u operator\n-P %s\nEOF\n" "$t" "$PW"; done; } | docker compose exec -T mosquitto sh -s; unset PW
O="docker compose exec -T -e XDG_CONFIG_HOME=/tmp/rfopt mosquitto"
$O mosquitto_sub -t 'rf/v1/dev/dev-emu-36/#' -v -F '%U %r %q %t %p'          # подписка, время хоста (%U)
# учётка агента: скрипт с am broadcast собирается в файл 600 и подаётся в `adb shell sh` (пароль не в argv)
adb -s emulator-5600 shell sh < /tmp/devcfg.sh   # am broadcast -a ru.restofleet.agent.DEV_CONFIG -n ru.restofleet.agent/.DevConfigReceiver --es host 10.0.2.2 --ei port 8883 --es deviceId dev-emu-36 --es password <из файла>
# M1 негатив: временный сервер на 18883 (ключи в mktemp -d, ca.key только путём), DEV_CONFIG с --ei port 18883
openssl s_server -accept 18883 -cert "$T/nosan.crt" -key "$T/nosan.key" -quiet   # SAN без 10.0.2.2; либо $T/foreign.{crt,key} — чужой CA
# M3: сеть
adb -s emulator-5600 shell 'svc wifi disable; svc data disable'   # enable — вернуть
# M4: retained desired (через stdin)
$O mosquitto_pub -r -q 1 -t rf/v1/dev/dev-emu-36/shadow/desired -s < ../docs/protocol/fixtures/shadow-desired.json
# M5/M6: команда со свежим cmdId и expiresAt (копия фикстуры), MQTT 5
$O mosquitto_pub -V 5 -q 1 -t rf/v1/dev/dev-emu-36/cmd/req -D publish response-topic rf/v1/dev/dev-emu-36/cmd/res \
  -D publish correlation-data c-1 -D publish message-expiry-interval 300 -s < /tmp/cmd.json
adb -s emulator-5600 shell am force-stop ru.restofleet.agent      # M6: остановить агент; затем am start -n ru.restofleet.agent/.MainActivity
# P4: простой брокера
docker compose stop mosquitto; sleep 125; docker compose start mosquitto     # подписку держать в цикле: она умирает вместе с брокером
# RS1/RS2
adb -s emulator-5600 shell dumpsys meminfo ru.restofleet.agent | grep 'TOTAL PSS'
adb -s emulator-5600 shell dumpsys netstats detail   # сумма rb+tb по uid агента (uid из /proc/<pid>/status), разница между замерами
# M7: сеть выключена, кнопка Lab «Сгенерировать N событий» (uiautomator dump -> узел по тексту -> input tap), затем сеть включить
```

Перед `./mosquitto/test-dynsec-dev.sh` остановить агент (`am force-stop`): тест подключается как `dev-emu-36`. Retained `status` устройства даёт ложное падение теста 1 (15/16): подписка `rf/v1/#` получает его раньше тестового сообщения.
