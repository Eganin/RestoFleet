# Этап 1 — Курсовая: ядро (протокол MQTT + агент)

> **Для новой сессии.** Прочитай `CLAUDE.md`, затем `docs/architecture.md` (разд. 1–10, 12), затем этот файл. Результаты MVP — `docs/stages/0-mvp-feasibility.md` (матрица) и ТЗ v0.3. Макеты: `docs/design/restofleet-blueprint.html#k`.

**Длительность:** ≈ 3 активных недели. **Тема:** «Разработка системы мониторинга и удалённого управления Android-устройствами на основе протокола MQTT».

---

## 1. Цель и результат

Работающее ядро IoT-платформы и экспериментальное обоснование MQTT. Минимум, на котором держится всё остальное: протокол, агент в обычном режиме, брокер с Dynamic Security, ядро бэкенда, симулятор, эксперимент. **Интерфейса консоли нет** — демонстрация через Swagger UI, MQTT Explorer и экраны агента.

## 2. Входные условия

- MVP-A закрыт: матрица заполнена для эмуляторов, ТЗ v0.3 согласовано.
- Есть каркас сборки, CI с Kover, `shared:kernel`, черновик `shared:protocol`, модули агента из MVP, compose с Mosquitto.
- Решено, на чём симулятор (Kotlin по умолчанию).

## 3. Объём

| Часть | Требования ТЗ |
|---|---|
| Протокол | разд. 7 + правки P1–P10 (`architecture.md` разд. 7) |
| Агент | FR-AG-01…05, 10, 12, 20…23, 25…27, 30…35 (включая фазы 33), 40…44, 50, 51, 60…64, 70, 75, 76, 78 — только то, что получило ✅/⚠️ в MVP |
| Брокер | FR-BR-01…03, 07 + базовый 04 (DynSec) |
| Бэкенд | FR-BE-01, 02, 04, 10, 11, 12, 20, 21, 40, 41, 50, 52 (одна роль), 54, 56 |
| Симулятор | FR-SM-01…03, 06, 07 + базовый 08 (регистрация через провижининг) |
| Эксперимент | ТЗ 13.2 |

**Не входит:** правила и тревоги (R-01…R-06), WebSocket, корреляция инцидентов, консоль (все — в П); Device Owner, профили, роли, Timescale, VPS (в Д).

## 4. Архитектура этапа

### 4.1 Модули
```
shared/protocol    финальные DTO + golden-фикстуры на все сообщения
shared/shadow      delta(desired, reported), сравнение версий, валидация параметров
shared/probes      автомат up↔down по K неудачам, классы ошибок, расписание
agent/*            + use cases К (см. 4.3), outbox на Room, провижининг, мастер и статус
server/domain      restaurant, device, provisioning, ingest, presence, shadow, command
server/data        Exposed + Flyway + HiveMQ ingest/publisher + DynSecBrokerAccounts + bcrypt
server/api         routes + DTO + StatusPages + OpenAPI/Swagger UI + JWT (одна роль admin)
server/app         main, Koin, конфиг (hoplite), CLI: migrate, seed (dev)
server/simulator   профили, сценарии YAML, CSV, MQTT и REST-polling режимы
deploy/            compose: mosquitto, postgres (timescale-pg17), backend, migrate, seed, toxiproxy, simulator
```

### 4.2 Бэкенд: use cases К
| Пакет domain | Use cases |
|---|---|
| restaurant | `CreateRestaurant`, `UpdateRestaurant`, `GetRestaurant`, `ListRestaurants` |
| device | `ListDevices`, `GetDevice`, `DecommissionDevice` |
| provisioning | `IssueProvisioningTokens` (один или пакет), `ProvisionDevice` (код → учётка DynSec + bcrypt-хэш) |
| ingest | `IngestBatch` (телеметрия, пробы, события → пакетная запись) |
| presence | `HandleStatus` (online/offline по `connId`) |
| shadow | `UpdateDesired` (версия++, retained publish после commit), `HandleReported` |
| command | `SendCommand`, `HandleCommandResult` (received → succeeded/failed/unsupported), `ExpireCommands` (планировщик) |

Порты domain: `RestaurantRepository`, `DeviceRepository`, `TokenRepository`, `TelemetryRepository`, `ShadowRepository`, `CommandRepository`, `BrokerAccounts`, `MessagePublisher`, `PasswordHasher`, `Clock`, `IdGenerator`.

### 4.3 Агент: use cases К
`CollectTelemetry`, `EvaluateThresholds` (события 40–44), `RunProbes` (30–35), `ApplyShadow` (50–51), `ExecuteCommand` (60–64, 70 по `Capabilities`), `FlushOutbox` (75, 76), `Provision` (10, 12), `MaintainConnection` (backoff + jitter, 78).

### 4.4 Модель данных (Flyway V1…)
`restaurants(id, code UNIQUE, name, city, address, timezone, created_at, updated_at)`, `devices(id, restaurant_id, role, name, status, last_seen_at, decommissioned_at)`, `device_credentials(device_id, password_hash, revoked_at)`, `provisioning_tokens(id, token_hash, restaurant_id, role, name, expires_at, used_at)`, `device_sessions(device_id, conn_id, connected_at, disconnected_at, reason)`, `shadows(device_id, desired jsonb, desired_version, reported jsonb, reported_version, applied_at)`, `telemetry(device_id, ts, battery_level, charging, temp_c, thermal, rssi, free_mb, avail_mb, fg_app, payload jsonb)`, `probe_results(device_id, ts, target, ok, status, error, total_ms, dns_ms, connect_ms, tls_ms, ttfb_ms)`, `events(device_id, ts, type, payload jsonb)`, `commands(cmd_id, device_id, type, params jsonb, status, created_at, expires_at, delivered_at, finished_at, result jsonb)`, `users(id, login, password_hash, role)`.

### 4.5 Рестораны
- **Продукт:** `POST/PATCH/GET /api/v1/restaurants` (FR-BE-01) — в К вызываются через Swagger UI, в Д — из Desktop-консоли.
- **Dev-seed стенда:** `deploy/seed/restaurants.yaml` (≈ 40 демо-ресторанов: код, название, город, адрес, часовой пояс) и `users.yaml` (admin). Одноразовый сервис `seed` в compose запускает `server:app seed`, который вызывает **те же** `CreateRestaurant`/`UpdateRestaurant` (upsert по `code`). Это тестовые данные для стенда и симулятора, не функция продукта; на VPS в Д не запускается.
- **Импорта из CSV нет.**

### 4.6 Симулятор и эксперимент
- Регистрация: `POST /provisioning/tokens:batch` (admin) → `/provision` на каждое виртуальное устройство; учётки кэшируются в файле. Устройства раскладываются по существующим ресторанам: на ресторан 2 кухни, 3 зала, 1 сборка (настраивается).
- Профили: кухня (на зарядке, нагрев у плиты в часы пик), зал (разряд в течение смены), сборка.
- Режимы: MQTT и REST-polling (`POST /poll/telemetry`, `GET /poll/commands`, Basic-аутентификация устройства).
- Эксперимент: N = 100 / 1 000 / 5 000; интервал телеметрии 30 с; опрос команд 5 / 15 / 30 с. Метрики: трафик на устройство, CPU/ОЗУ сервера (`docker stats`), задержка команды p50/p95, время обнаружения пропажи. Деградация: задержка 300 мс — Toxiproxy перед брокером и бэкендом; потери 5 % — `tc netem` в контейнере симулятора (`cap_add: NET_ADMIN`). Симулятор — на отдельной машине или с ограничением CPU через compose.

### 4.7 Демонстрация без консоли
Swagger UI бэкенда (создать ресторан, выпустить код, посмотреть устройство, тень, отправить команду), MQTT Explorer (поток сообщений), агент на эмуляторе (мастер, статус, Capability Lab). Если на защите нужен интерфейс — запасной вариант: два экрана консоли (список и карточка устройства), ≈ 2 дня, по канону из `architecture.md` разд. 5.

## 5. Задачи

### К1. Протокол (2 дня)
- [x] Финальные DTO `shared:protocol` по ТЗ 7.2 + P1–P10; фикстуры на каждое сообщение и каждый статус команды.
- [x] `shared:shadow`, `shared:probes` — TDD, ≥ 90 %.

### К2. Агент — обычный режим (5 дней)
- [ ] Use cases из 4.3 с тестами (фейки портов).
- [ ] Data: MQTT-сессия (LWT с `connId`, Clean Start=false, Session Expiry 24 ч), outbox на Room, провижининг + Keystore.
- [ ] Presentation: мастер настройки, экран статуса.
- [ ] Ночной прогон на эмуляторе: телеметрия без пропусков, outbox после 1 ч офлайна.

### К3. Брокер и ядро бэкенда (5 дней)
- [x] Mosquitto: роль `device` с ACL по направлениям, `clientid = deviceId`, persistence.
- [ ] `server:domain` — use cases 4.2 (TDD); `server:data` — репозитории, миграции, ingest с ack после commit, DynSec-клиент; `server:api` — routes + OpenAPI/Swagger; `server:app` — Koin, конфиг, CLI `migrate`/`seed`.
- [ ] Dev-seed ресторанов и пользователя admin.
- [x] Интеграционные тесты (Testcontainers): provision → telemetry → БД; команда офлайн-устройству → доставка / `expired`; отозванная учётка не подключается.

### К4. Симулятор и эксперимент (3 дня)
- [ ] Симулятор по 4.6, тесты генераторов профилей.
- [ ] Прогон эксперимента, CSV → таблицы и графики в `docs/experiments/`.
- [ ] Пояснительная записка (структура — ТЗ 16.3).

## 6. Тесты

Domain бэкенда и агента ≥ 90 %, проект ≥ 80 % (Kover-гейт). Ktor routes — `testApplication`. Exposed/Flyway/MQTT — Testcontainers. Безопасность: публикация в чужой топик, подключение отозванной учётки, подключение без TLS.

## 7. Приёмка

- **S1** — провижининг через Swagger → код в агенте → устройство `online` в `GET /devices`.
- **S2′** — выключить сеть эмулятора → статус `offline` ≈ 50 с → включить → буфер дослан (тревоги — в П).
- **S4** — изменение `desired` → агент применил → `delta` пуста.
- **S5** — команда офлайн-устройству доставлена при подключении; просроченная → `expired`.
- **S7′** — Toxiproxy отключает `api` → событие `probe_state: down` в БД.
- Эксперимент на N ≥ 1 000; `docker compose up` поднимает стенд; Kover-гейт зелёный.

## 8. Передаётся в практику

Стабильный протокол и фикстуры, агент обычного режима, бэкенд с API и OpenAPI, наполненный стенд, симулятор с профилями, результаты эксперимента.

## 9. Риски

- Курсовая без интерфейса консоли — согласовать с преподавателем заранее; запасной вариант — 2 экрана (≈ 2 дня).
- Нагрузка 5 000 на одной машине с сервером искажает замеры — ограничить CPU симулятора или вынести на вторую машину.
