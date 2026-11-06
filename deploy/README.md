# Стенд RestoFleet (MVP)

Mosquitto 2.1.2: только TLS на `8883`, анонимы запрещены, учётки — Dynamic Security, persistence на томе `restofleet_mosquitto-data`.

## Запуск (из каталога `deploy/`)

```bash
cp .env.example .env && sed -i '' "s|change-me|$(openssl rand -base64 32 | tr -d '/+=')|" .env   # один раз
./certs/make-ca.sh                                        # CA (один раз) + серверный сертификат; LAN-IP — аргументом
docker compose --profile init run --rm dynsec-init        # один раз: создаёт admin в dynamic-security.json
docker compose up -d --wait                               # брокер, ждёт healthy
./test-stand.sh                                           # smoke: TLS, отказ анонимам и чужому паролю, нет 1883, нет ca.key и пароля в контейнере, persistence
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
| `mosquitto/mosquitto.conf` | конфигурация брокера | да |
| `mosquitto/init-dynsec.sh` | однократная инициализация DynSec | да |
| `.env` | `DYNSEC_ADMIN_PASSWORD` | **нет** |

SAN серверного сертификата: `localhost`, `mosquitto`, `apk-host`, `127.0.0.1`, `10.0.2.2` (хост из эмулятора), IP машины в LAN (реальные устройства, MVP-B). После смены сети машины — перезапустить `./certs/make-ca.sh <новый IP>` и `docker compose restart mosquitto`; CA при этом не меняется, агент перевыпуска не требует.

## Подключение вручную

```bash
docker compose exec mosquitto mosquitto_sub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt \
  -u admin -P "$DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1
```

**`admin` после `dynsec init` имеет полный доступ**: управляет DynSec (`$CONTROL/dynamic-security/#`) и может читать все топики (`subscribePattern #`). Поэтому его пароль хранится только в `deploy/.env` и передаётся лишь одноразовому `dynsec-init` — у брокера его нет ни в окружении, ни в healthcheck (healthcheck проверяет, что анонимный клиент получает CONNACK «not authorised»). Роли `device` и `operator-dev` и учётки устройств создаёт `mosquitto/dynsec-dev.sh` (задача 3.1).

## Сброс стенда

```bash
docker compose down -v      # удаляет том: DynSec-учётки и persistence пропадут
```
