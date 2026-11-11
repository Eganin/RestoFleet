# Стенд RestoFleet

| Сервис | Что | Порт на хосте |
|---|---|---|
| `mosquitto` | Mosquitto 2.1.2: только TLS, анонимы запрещены, учётки — Dynamic Security, persistence (том `restofleet_mosquitto-data`) | `8883` (все интерфейсы: реальные устройства в LAN) |
| `apk-host` | nginx 1.30.6 — HTTPS-шлюз стенда: раздача APK и прокси `/api/`, `/health`, `/swagger` на бэкенд (реш. 211) | `8443` |
| `postgres` | PostgreSQL 17 + TimescaleDB 2.31 (том `restofleet_postgres-data`) | не публикуется |
| `migrate` | одноразово: миграции Flyway (образ бэкенда, команда `migrate`) | — |
| `backend` | бэкенд Ktor (`serve`): REST API, ingest MQTT по TLS, фоновые задачи | `127.0.0.1:8080` |
| `seed` | profile `dev`, одноразово: 40 вымышленных ресторанов и `admin` (`deploy/seed/*.yaml`) | — |
| `toxiproxy` | Toxiproxy 2.12.0: прокси `api`, `mqtt`, `rest` для деградации сети | `127.0.0.1:8474` (управление), `18443`, `18883`, `18080` |

## Запуск с нуля (из каталога `deploy/`)

```bash
./prepare-stand.sh                              # идемпотентно: .env, CA и сертификат, секреты deploy/out/*, DynSec
                                                # (admin, роли, учётка backend), образ бэкенда (installDist + build)
docker compose --profile dev up -d --wait       # весь стенд одной командой: брокер, nginx, postgres, migrate,
                                                # backend, seed, toxiproxy
./test-backend.sh                               # smoke бэкенда (16 проверок, RED → GREEN в КМ2b)
```

Без `--profile dev` стенд поднимается без `seed` (рестораны и `admin` не обновляются). После правки кода бэкенда: `(cd .. && ./gradlew :server:app:installDist) && docker compose build backend && docker compose up -d --wait backend`.

Только брокер и APK-хост (как в MVP): `docker compose up -d --wait mosquitto apk-host`. Скрипты MVP: `./test-stand.sh` (TLS, анонимы, APK-хост, persistence; нужен `../tools/dev/build-dummy-apk.sh 13`), `./certs/test-make-ca.sh`, `mosquitto/test-dynsec-dev.sh`.

## Бэкенд

- **Swagger UI:** `https://localhost:8443/swagger` (CA стенда в доверенных браузера или исключение; ассеты Swagger UI грузятся с unpkg.com — нужен интернет). Кнопка **Authorize** → `accessToken` из `POST /api/v1/auth/login`. Спецификация — `docs/openapi.yaml`.
- **Вход:** пользователь `admin`, пароль — файл `out/admin-password` (600). В оболочке, не светя пароль в argv:

```bash
printf '{"login":"admin","password":"%s"}' "$(cat out/admin-password)" > /tmp/rf-login.json   # printf — встроенная
curl -s --cacert certs/out/public/ca.crt -H 'Content-Type: application/json' --data-binary @/tmp/rf-login.json \
  https://localhost:8443/api/v1/auth/login | python3 -I -c 'import json,sys; print("Authorization: Bearer "+json.load(sys.stdin)["accessToken"])' > /tmp/rf-auth
rm /tmp/rf-login.json
curl -s --cacert certs/out/public/ca.crt -H @/tmp/rf-auth https://localhost:8443/api/v1/restaurants
```

- **Код регистрации и провижининг** (как агент; из эмулятора — `https://10.0.2.2:8443`):

```bash
curl -s --cacert certs/out/public/ca.crt -H @/tmp/rf-auth -H 'Content-Type: application/json' \
  -d '{"restaurantId":"<id ресторана>","role":"kitchen","name":"Кухня-1"}' https://localhost:8443/api/v1/provisioning/tokens
curl -s --cacert certs/out/public/ca.crt -H 'Content-Type: application/json' \
  -d '{"code":"<код>","deviceInfo":{"model":"m","manufacturer":"g","osVersion":"16","apiLevel":36},"agentVersion":"1.0.0"}' \
  https://localhost:8443/api/v1/provision        # → deviceId, password (один раз), mqtt {host: 10.0.2.2, port: 8883}
```

- **CLI образа** (`restofleet-backend`): `serve` (по умолчанию), `migrate`, `seed --restaurants <yaml> --users <yaml> [--secrets-dir <dir>]`. Повторить seed: `docker compose --profile dev run --rm seed`.
- **Конфигурация:** `server/app/src/main/resources/application.yaml` (значения стенда) + переменные `RF_<РАЗДЕЛ>_<КЛЮЧ>` (`RF_PROVISIONING_BROKERHOST`, `RF_RATELIMIT_PROVISIONPERMINUTE`, …; часть вынесена в `.env`, см. `.env.example`). Секретов в конфигурации и окружении нет — только пути к файлам (`/run/secrets/*`, реш. 252).
- **Журналы:** `docker compose logs -f backend` — одна строка на вызов API (статус, метод, путь без query, время), без заголовков и тел; счётчики ingest — раз в минуту при изменении.

## Toxiproxy

Прокси (`toxiproxy.json`), слушают на хосте только `127.0.0.1`:

| Прокси | Слушает | Upstream | Для чего |
|---|---|---|---|
| `api` | `18443` | `apk-host:443` (nginx → `/health`, `/api/`) | цель проб агента и симулятора: из эмулятора `https://10.0.2.2:18443/health` (S7′) |
| `mqtt` | `18883` | `mosquitto:8883` | брокер с задержкой/потерями (SAN сертификата: `toxiproxy`, `localhost`, `127.0.0.1`, `10.0.2.2`) |
| `rest` | `18080` | `backend:8080` (HTTP) | REST-polling симулятора с деградацией |

Управление — REST API на `127.0.0.1:8474`:

```bash
curl -s localhost:8474/proxies                                                    # состояние
curl -s -X POST localhost:8474/proxies/api -d '{"enabled":false}'                 # «отказ цели» (S7′); true — вернуть
curl -s -X POST localhost:8474/proxies/mqtt/toxics \
  -d '{"name":"lat300","type":"latency","stream":"downstream","attributes":{"latency":300}}'   # +300 мс
curl -s -X DELETE localhost:8474/proxies/mqtt/toxics/lat300                       # убрать
curl -s -X POST localhost:8474/reset                                              # всё включить, токсины снять
```

Конфигурация из `toxiproxy.json` применяется при старте контейнера; изменения через API живут до перезапуска.

## Эксперимент (КМ4): симулятор и «MQTT vs REST-polling»

Сервис `simulator` (profile `experiment`, образ `restofleet/simulator:dev`) — виртуальные устройства и измеритель. Запускается только оркестратором из корня репозитория (он не трогает `.env`: бэкенд пересоздаётся с нужными `RF_*` в окружении команды, симулятор — `run --rm --no-deps`):

```bash
tools/experiments/run-experiment.sh build              # installDist + образ симулятора
tools/experiments/run-experiment.sh register 5000      # дорегистрировать устройства до N; кэш учёток out/sim/creds.jsonl (600)
tools/experiments/run-experiment.sh run smoke-mqtt     # ≈ 2 мин: 20 устройств, проверка цепочки (также smoke-rest)
tools/experiments/run-experiment.sh run mqtt-1000      # прогон: tools/experiments/scenarios/<имя>.yaml
tools/experiments/run-experiment.sh restore            # бэкенд с умолчаниями, Toxiproxy — reset
/tmp/rf-venv/bin/python -I tools/experiments/analyze.py # CSV → docs/experiments/ (matplotlib — в venv вне репозитория)
```

- **Сети:** устройства ходят по `default` (`mosquitto:8883`, `backend-devices:8080`, через Toxiproxy — `toxiproxy:18883`/`18080`), измеритель — по внутренней сети `control` (`backend-ctl:8080`): его запросы не попадают в счётчик трафика устройств (реш. 280).
- **Деградация** задаётся именем сценария: `…-lat300` — токсин Toxiproxy +300 мс на прокси `mqtt` и `rest` (снимается после прогона), `…-loss5` — `tc netem loss 5%` в контейнере симулятора (`RF_SIM_NETEM`, нужен `NET_ADMIN`; точка входа затем переходит на uid 10001). `pollIntervalSec: P` → `RF_POLLING_STALEAFTERMS` = 3 × P с на время прогона (реш. 214).
- **Ресурсы симулятора:** `RF_SIM_CPUS` (2), `RF_SIM_MEM` (3g), `RF_SIM_HEAP` (2g).
- **Результаты:** `logs/experiments/<прогон>/` (вне git): `summary.csv`, `commands.csv`, `detection.csv`, `timeline.csv` (симулятор), `docker-stats.csv` (сэмплер, 5 с), `conditions.txt`, журнал. Паролей в них нет.
- **Устройства симулятора остаются на стенде** (offline) и переиспользуются следующими прогонами. Удалить: `DELETE /api/v1/devices/{id}` по `deviceId` из кэша, затем удалить `out/sim/creds.jsonl`.

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
| `mosquitto/dynsec-roles.sh` | роли продукта `device` и `backend-service` (общий файл: его подключают `dynsec-dev.sh` и тесты `server:data`) | да |
| `mosquitto/dynsec-dev.sh` | роли `device`, `backend-service`, `operator-dev`, dev-клиенты и учётка `backend` (идемпотентно) | да |
| `mosquitto/test-dynsec-dev.sh` | проверка ACL и идемпотентности на живом стенде | да |
| `out/dev-credentials.txt` | пароли dev-клиентов, `<username> <password>`, права 600 | **нет** |
| `out/backend-mqtt-password` | пароль учётки `backend` (одна строка, права 600; создаёт `dynsec-dev.sh`); монтируется бэкенду как секрет | **нет** |
| `out/postgres-password`, `out/jwt-secret`, `out/admin-password` | пароль БД, секрет подписи JWT, пароль `admin` бэкенда (600; создаёт `prepare-stand.sh`, если файла нет). Монтируются compose `secrets` в `/run/secrets/*` только нужным сервисам | **нет** |
| `seed/restaurants.yaml`, `seed/users.yaml` | dev-seed: 40 вымышленных ресторанов; `admin` с **именем** секрета, без пароля | да |
| `toxiproxy.json` | прокси `api`, `mqtt`, `rest` | да |
| `prepare-stand.sh`, `test-backend.sh` | подготовка стенда и smoke бэкенда | да |
| `.env` | `DYNSEC_ADMIN_PASSWORD` и параметры бэкенда (`RF_*`, без секретов) | **нет** |
| `out/sim/creds.jsonl` | учётки виртуальных устройств симулятора (`deviceId`, пароль, ресторан, роль), 600, каталог 700 | **нет** |

SAN серверного сертификата: `localhost`, `mosquitto`, `apk-host`, `backend`, `toxiproxy`, `127.0.0.1`, `10.0.2.2` (хост из эмулятора), IP машины в LAN (реальные устройства, MVP-B). После смены сети машины — перезапустить `./certs/make-ca.sh <новый IP>` и `docker compose restart mosquitto`; CA при этом не меняется, агент перевыпуска не требует.

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
# Пароль admin — только в файле опций 600 внутри контейнера, не в argv (mosquitto/broker-opts.sh, реш. 308)
( set -a; . ./.env; set +a; . mosquitto/broker-opts.sh
  { broker_opts_begin; broker_opts_client as_admin admin "$DYNSEC_ADMIN_PASSWORD"
    echo 'as_admin mosquitto_sub -i rf-admin-sys -t "\$SYS/broker/version" -t "\$SYS/broker/shared_subscriptions/count" -C 2 -W 15 -v'
  } | docker compose exec -T mosquitto sh -s )
```

`$SYS/broker/shared_subscriptions/count` = 6 — бэкенд подписан на все каналы устройств. С V-1 (реш. 301) бэкенд подписывается заново на каждом переподключении, а `/health` показывает `broker: true` только после SUBACK на текущем соединении; `test-backend.sh` после перезапуска брокера проверяет, что новая телеметрия доходит до БД.

Пределы брокера (`mosquitto.conf`, реш. 302, 303): пакет до 260 КиБ (`max_packet_size`, тот же предел объявляет бэкенд), очередь сессии до 200 000 QoS 1 сообщений и 128 МиБ (`max_queued_messages`/`max_queued_bytes`, ≈ 40 мин недоступности бэкенда или БД при 5 000 устройств), куча до 1 ГиБ (`memory_limit`). QoS 0 (живая телеметрия и пробы) для отключённого бэкенда не копится.

**`admin` после `dynsec init` имеет полный доступ**: управляет DynSec (`$CONTROL/dynamic-security/#`) и может читать все топики (`subscribePattern #`). Поэтому его пароль хранится только в `deploy/.env` и передаётся лишь одноразовому `dynsec-init` — у брокера его нет ни в окружении, ни в healthcheck (healthcheck проверяет, что анонимный клиент получает CONNACK «not authorised»). Роли `device`, `backend-service`, `operator-dev`, учётку `backend` и учётки устройств создаёт `mosquitto/dynsec-dev.sh` (см. ниже): офлайн `mosquitto_ctrl` умеет только `dynsec init`, поэтому роли — на работающем брокере (реш. 231).

## Учётки устройств (DynSec)

```bash
mosquitto/dynsec-dev.sh        # из deploy/, после `docker compose up -d --wait`
mosquitto/test-dynsec-dev.sh   # проверка ACL: PASS/FAIL, код возврата != 0 при провале
```

Скрипт создаёт роль `device` (по умолчанию всё запрещено): публикация в `rf/v1/dev/%u/{status,telemetry,probe,events,shadow/reported,cmd/res}`, подписка и получение только `rf/v1/dev/%u/shadow/desired` и `rf/v1/dev/%u/cmd/req`. `%u` — имя клиента, оно же `clientId` (`setClientId`), поэтому устройство не может ни подключиться под чужим `clientId`, ни писать в чужие топики. Клиенты: `dev-emu-26` … `dev-emu-37` (эмуляторы по уровню API), `dev-do-26`, `dev-do-36` (эмуляторы с Device Owner), `dev-acl-test` (роль `device`, только субъект `test-dynsec-dev.sh`: проверка не вытесняет работающих агентов), `operator` (роль `operator-dev`: всё на `rf/v1/#`, `clientId` не фиксирован). `test-dynsec-dev.sh` подключает оператора с `clientId` `acl-test-op-*`, поэтому живую подписку `op-sub` не трогает; перед первым прогоном после обновления выполните `mosquitto/dynsec-dev.sh`, он добавит `dev-acl-test`.

Учётка бэкенда (реш. 230): клиент `backend` с `clientId` `backend` и ролью `backend-service` — shared-подписка `$share/backend/rf/v1/dev/+/{status,telemetry,probe,events,shadow/reported,cmd/res}`, публикация `shadow/desired`, `cmd/req` и `status` (только очистка retained) любому устройству, `$CONTROL/dynamic-security/#` (создание, отключение и удаление учёток устройств). Пароль создаётся один раз, если клиента ещё нет, и пишется только в `out/backend-mqtt-password`. `test-dynsec-dev.sh` проверяет клиента и роль через `getClient`/`getRole`, но не подключается как `backend`: это вытеснило бы работающий бэкенд. Живые проверки ACL этой роли — в интеграционных тестах `server:data` (тот же `dynsec-roles.sh`).

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
docker compose --profile dev --profile init down -v   # удаляет тома: DynSec-учётки, persistence брокера и БД пропадут
rm out/postgres-password    # только вместе с томом БД: пароль задаётся при первой инициализации тома
```

Сбросить только БД: `docker compose stop backend postgres && docker compose rm -f postgres && docker volume rm restofleet_postgres-data`, затем `docker compose --profile dev up -d --wait` (миграции и seed пройдут заново).

## Проверки MVP

Цепочка M1–M7, P4, RS1–RS2 на эмуляторе `rf-api36` (результаты — в `docs/stages/0-mvp-feasibility.md`). Пароли в аргументы не попадают: опции оператора лежат в файлах контейнера, учётка агента передаётся через stdin оболочки устройства.

```bash
# опции оператора внутри контейнера (пароль читается из out/dev-credentials.txt, не печатается)
cd deploy; PW=$(awk '$1=="operator"{print $2}' out/dev-credentials.txt)
{ printf 'umask 077\nmkdir -p /tmp/rfopt\n'; for t in mosquitto_sub mosquitto_pub; do
  printf "cat > /tmp/rfopt/%s <<'EOF'\n-h localhost\n-p 8883\n-V mqttv5\n--cafile /mosquitto/certs/ca.crt\n-u operator\n-P %s\nEOF\n" "$t" "$PW"; done; } | docker compose exec -T mosquitto sh -s; unset PW
O() { docker compose exec -T -e XDG_CONFIG_HOME=/tmp/rfopt mosquitto "$@"; }   # функция: работает и в bash, и в zsh
O mosquitto_sub -t 'rf/v1/dev/dev-emu-36/#' -v -F '%U %r %q %t %p'          # подписка, время хоста (%U)
# учётка агента: скрипт с am broadcast собирается в файл 600 и подаётся в `adb shell sh` (пароль не в argv)
adb -s emulator-5600 shell sh < /tmp/devcfg.sh   # am broadcast -a ru.restofleet.agent.DEV_CONFIG -n ru.restofleet.agent/.DevConfigReceiver --es host 10.0.2.2 --ei port 8883 --es deviceId dev-emu-36 --es password <из файла>
# M1 негатив: временный сервер на 18883 (ключи в mktemp -d, ca.key только путём), DEV_CONFIG с --ei port 18883
openssl s_server -accept 18883 -cert "$T/nosan.crt" -key "$T/nosan.key" -quiet   # SAN без 10.0.2.2; либо $T/foreign.{crt,key} — чужой CA
# M3: сеть
adb -s emulator-5600 shell 'svc wifi disable; svc data disable'   # enable — вернуть
# M4: retained desired (через stdin)
O mosquitto_pub -r -q 1 -t rf/v1/dev/dev-emu-36/shadow/desired -s < ../docs/protocol/fixtures/shadow-desired.json
# M5/M6: команда со свежим cmdId и expiresAt (копия фикстуры), MQTT 5
O mosquitto_pub -V 5 -q 1 -t rf/v1/dev/dev-emu-36/cmd/req -D publish response-topic rf/v1/dev/dev-emu-36/cmd/res \
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
