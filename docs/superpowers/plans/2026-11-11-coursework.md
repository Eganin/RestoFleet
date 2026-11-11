# Курсовая (К): план реализации

> **Для агентов-исполнителей.** Перед работой прочитать: `CLAUDE.md`, `docs/handoff.md` (разд. «Старт этапа К»), этот план (свою веху целиком + «Общие правила»), `docs/reports/1-coursework/decisions.md`. Спецификация — `docs/stages/1-coursework.md`, `docs/architecture.md` (разд. 3–10, 12), `TZ_RestoFleet.md` (разд. 6, 7, 8, 13.2). Шаги — чекбоксы `- [ ]`; отмечать `[x]` по мере выполнения.

**Цель:** работающее ядро платформы на стенде — финальный протокол, агент обычного режима с провижинингом по коду, брокер с DynSec, бэкенд Ktor (реестр, провижининг, приём, тени, команды, REST API + Swagger, REST-polling), симулятор и эксперимент «MQTT vs REST-polling»; сценарии S1, S2′, S4, S5, S7′; пояснительная записка в `.docx`.

**Ветка:** `stage/1-coursework` (от `main` @ `c978ffe`). В `main` не сливать, теги не ставить, не пушить без просьбы.

**Исполнение:** ночной прогон без пользователя (реш. 200–217). Вехи идут **последовательно** одним исполнителем на веху (два коммитящих агента в одном worktree не работают одновременно). Ревью — одно на этап после КМ4 (opus), затем одна валидация стенда и эмулятора, один раунд исправлений.

---

## Общие правила (для всех вех)

- **Код:** Kotlin; Clean Architecture (domain без Android, Ktor, Exposed, HiveMQ, kotlinx-serialization, Koin); Koin только через конструктор, `get()`/`inject()` — только в composition root (`agent:app`, `server:app`, `Application.module`). Без convention-плагинов: свой `build.gradle.kts` у модуля, все версии и плагины — `gradle/libs.versions.toml`.
- **TDD:** тест пишется и падает до реализации. JUnit 4 + **Truth**, ручные фейки портов в domain, MockK — только JVM/Android-модули, Turbine для Flow, Ktor `testApplication`, Testcontainers для Exposed/Flyway/Mosquitto.
- **Покрытие:** новый модуль подключается к корневому Kover-агрегатору (`kover(projects.…)`) и своему варианту `ci`; domain ≥ 90 %, проект ≥ 80 %.
- **Гейт перед каждым коммитом:** `./gradlew build detekt koverVerifyCi :konsist-tests:test --continue` → **rc = 0**. Коммит — только после rc = 0 (не до конца сборки). Docker должен быть запущен (Testcontainers, реш. 215).
- **Коммиты:** Conventional Commits, по-русски или по-английски, **без** `Co-Authored-By` и `Generated with`. WIP-коммиты допустимы только с зелёным гейтом.
- **Секреты:** не выводить и не коммитить `deploy/.env`, `deploy/out/`, `deploy/certs/out/`, `deploy/apk/`; пароли — через файлы и stdin, не argv/echo. Проверка утечки — только числом.
- **Konsist:** расширить правила на `server:*` (domain без фреймворков, один `invoke` у use case, Koin только в composition root).
- **Отчётность (каждая веха):** раздел «Прогресс» в `docs/reports/1-coursework/K<n>.md` ведётся по ходу (на случай обрыва API), в конце — метрики (тесты, покрытие), решения (в `decisions.md`, номера ≥ 218 по порядку, проверить последний номер перед записью), отклонения, что не проверено. Обновить `docs/handoff.md` («Текущее состояние» + строка журнала) и отметить `[x]` в этом плане и в `docs/stages/1-coursework.md`.
- **Окружение:** macOS, `python3 -I`, `timeout` нет; свободного места ≥ 12 ГБ (проверять `df -h .` перед тяжёлыми шагами, `docker image prune` только своих образов). Эмулятор — только свои AVD `rf-api36`/`rf-api36-do`, `-no-window -no-audio -no-boot-anim -port 5600`; чужие AVD не трогать.
- **Неоднозначность** — Ruling в `decisions.md`, не вопрос пользователю. Остановка — только на необратимом/небезопасном.

## Версии новых зависимостей (Maven Central, 11.11.2026)

| Что | Версия | Примечание |
|---|---|---|
| Ktor (server, client) | 3.6.1 | Netty, ContentNegotiation, StatusPages, Auth + Auth-JWT, RateLimit, CallLogging, `ktor-server-swagger`, test-host |
| Exposed | 1.6.1 | core, jdbc, java-time (или kotlin-datetime), json |
| Flyway | 13.12.1 | + `flyway-database-postgresql` |
| PostgreSQL JDBC | 42.7.14 | |
| HikariCP | 7.1.0 | |
| Testcontainers | 2.0.5 | артефакты 2.x: `testcontainers-postgresql`, `testcontainers-junit-jupiter` не нужен (JUnit 4 — `GenericContainer` вручную/`@ClassRule`) |
| hoplite | 3.0.7 | core + yaml |
| kaml | 0.104.0 | YAML сценариев симулятора и dev-seed |
| bcrypt (favre) | 0.10.2 | |
| java-jwt (auth0) | 4.6.2 | транзитивно через `ktor-server-auth-jwt` — версию не дублировать без нужды |
| logback-classic | 1.6.6 | |
| kotlin-logging-jvm | 8.0.7 | |
| PostgreSQL образ | `timescale/timescaledb:2.x-pg17` (точный тег проверить на Docker Hub, напр. `2.22.1-pg17`) | гипертаблицы — Д (D11) |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.12.0` (проверить тег) | |

Перед добавлением — проверить, что версия резолвится (`./gradlew :module:dependencies`).

---

## КМ1. Протокол: `shared:protocol` (финал), `shared:shadow`, `shared:probes` (≈ 2 дня)

Отчёт: `docs/reports/1-coursework/K1.md`.

- [x] **1.1 Аудит протокола.** Сверить DTO `shared:protocol` с ТЗ 7.2 + P1–P10 + дополнения v0.3 (7.4). Список расхождений — в K1.md.
- [x] **1.2 Событие `threshold` (реш. 202).** `{ts, type:"threshold", metric:"battery_low"|"temp_high"|"thermal", state:"enter"|"exit", value, limit}` — DTO + фикстуры `event-threshold-enter.json`, `event-threshold-exit.json`. Событие `fg_absent` (FR-AG-41: отслеживаемое приложение не на экране дольше N мин) и `fg_changed` (смена приложения на переднем плане) — DTO + фикстуры.
- [x] **1.3 Фикстуры на каждое сообщение и каждый статус команды:** `cmd-res-succeeded.json` (с `result`), `cmd-res-failed-expired.json`, `cmd-res-failed-invalid-params.json`, `cmd-req-ping.json`, `cmd-req-collect-diagnostics.json`, `cmd-req-launch-app.json`, `cmd-req-run-probe.json`, `shadow-reported-errors.json`, `status-offline-stop.json`. Round-trip тест (`FixtureRoundTripTest`) покрывает все файлы каталога (тест падает на новой фикстуре без DTO).
- [x] **1.4 REST DTO провижининга и polling** в `shared:protocol` (`rest/`): `ProvisionRequestDto{code, deviceInfo{model, manufacturer, osVersion, apiLevel}, agentVersion}`, `ProvisionResponseDto{deviceId, password, restaurantId, role, mqtt{host, port}}`; `PollTelemetryRequestDto` (= телеметрия + опционально `probe`/`events`), `PollCommandsResponseDto{commands:[CommandRequestDto]}`, `PollCommandResultDto` (= `cmd/res`, `POST /poll/commands/result`). Фикстуры в `docs/protocol/fixtures/rest-*.json`.
- [x] **1.5 `shared:shadow` (KMP, domain-код без сериализации):** модель `ShadowDocument` (дерево ключ → значение: `ShadowValue` sealed — Num/Str/Bool/List/Obj), `delta(desired, reported): ShadowDocument` (рекурсивно; списки сравниваются целиком; ключи `version`, `appliedVersion`, `appliedAt`, `errors` игнорируются), `VersionDecision.of(stored, incoming)` → `IGNORE_OLDER | ECHO_EQUAL | APPLY_NEWER`, валидация параметров (`validateDesired` → список кодов ошибок ТЗ 7.2: `telemetryIntervalSec:out_of_range`, `probes:empty_id`, `probes:empty_url:<id>`, `probes:duplicate_id:<id>`, `watchedApps:empty`, `watchedApps:invalid_name:<имя>`) — перенести логику из `ApplyDesiredShadow` агента, не дублировать. TDD, ≥ 90 %, табличные тесты.
- [x] **1.6 `shared:probes` (KMP):** `ProbeStateMachine` (K подряд неудач → `DOWN`, первый успех → `UP`; начальное `UNKNOWN`; переход возвращает `Transition?` — событие отправляется только при смене состояния), `ProbeErrorClass` (`dns_fail`, `timeout`, `conn_refused`, `tls_error`, `http_4xx`, `http_5xx`, `no_network`, `local_network_denied`, `other`) + `classifyHttpStatus(code, expected)`, `ProbeSchedule` (следующий запуск по `intervalSec`, не чаще 1 раза в 30 с к цели — НФТ «нагрузка проверок», минимальный интервал настраивается, по умолчанию 30 с; для стенда допускается 5 с флагом). Если классификатор ошибок уже есть в `agent:domain` — перенести в `shared:probes`, агент ссылается на общий.
- [x] **1.7** Подключить модули в `settings.gradle.kts`, Kover-агрегатор, detekt, Konsist. Гейт → коммиты по подзадачам. K1.md, handoff.

**Приёмка КМ1:** все фикстуры каталога проходят round-trip; `shared:shadow` и `shared:probes` ≥ 90 % строк; гейт rc=0.

---

## КМ2. Бэкенд и брокер (≈ 5 дней)

Отчёт: `docs/reports/1-coursework/K2.md`. Модули: `server:domain` (JVM), `server:data` (JVM), `server:api` (JVM), `server:app` (JVM, `application`). Пакеты `ru.restofleet.server.<layer>.<feature>`.

### 2.1 `server:domain` (TDD, ≥ 90 %)
- [x] Сущности: `Restaurant(id, code, name, city, address, timezone)`, `Device(id, restaurantId, role: DeviceRole{KITCHEN,HALL,ASSEMBLY}, name, status: DeviceStatus{PROVISIONED,ONLINE,OFFLINE,DECOMMISSIONED}, lastSeenAt, decommissionedAt)`, `ProvisioningToken`, `DeviceSession(connId…)`, `Shadow(desired, desiredVersion, reported, reportedVersion, appliedAt)` (на `shared:shadow`), `Command(cmdId, deviceId, type, params, status: CommandStatus{QUEUED,DELIVERED,SUCCEEDED,FAILED,UNSUPPORTED,EXPIRED}, createdAt, expiresAt, deliveredAt, finishedAt, result, reason)`, `TelemetrySnapshot`, `ProbeReport`, `DeviceEvent`, `User`.
- [x] Порты: `RestaurantRepository`, `DeviceRepository`, `TokenRepository`, `CredentialRepository`, `SessionRepository`, `TelemetryRepository` (пакетная запись телеметрии/проб/событий), `ShadowRepository`, `CommandRepository`, `UserRepository`, `RefreshTokenRepository`, `BrokerAccounts` (create/disable/delete клиента DynSec), `MessagePublisher` (desired retained, очистка retained, `cmd/req` с properties), `PasswordHasher`, `TokenGenerator`/`SecretGenerator`, `Clock`, `IdGenerator`, `TransactionRunner` (use case задаёт границу транзакции без Exposed).
- [x] Use cases (один `operator fun invoke`): `CreateRestaurant`, `UpdateRestaurant`, `GetRestaurant`, `ListRestaurants`, `UpsertRestaurantByCode` (для seed — вызывает Create/Update); `ListDevices(restaurant, role, status)`, `GetDevice` (+ последняя телеметрия), `DecommissionDevice` (отзыв: DynSec disable + `revoked_at` + очистка retained `desired`/`status` + сброс кэша Basic); `IssueProvisioningTokens(restaurantId, role, name?, count 1..1000)` (128 бит, в БД SHA-256, TTL 24 ч; код отдаётся один раз), `ProvisionDevice(code, deviceInfo)` (атомарное погашение токена → device + пароль 32 байта → bcrypt-хэш → DynSec-клиент `clientid = deviceId`, роль `device`; при сбое DynSec — откат); `IngestBatch` (телеметрия/пробы/события пачкой; неизвестное устройство или выведенное — отбросить с подсчётом); `HandleStatus` (online/offline по `connId`: offline с чужим `connId` игнорируется; история `device_sessions`); `UpdateDesired` (версия++, сохранение, publish retained **после** commit), `HandleReported` (сохранить `reported`, `appliedVersion`); `SendCommand` (`queued`, `expiresAt = now + ttl`, publish `cmd/req` с Response Topic/Correlation Data/Message Expiry), `HandleCommandResult` (идемпотентно по `(cmdId, status)`; `received` → `delivered`; итог побеждает `expired`), `ExpireCommands` (по таймеру: `queued/delivered` с `expiresAt < now` → `expired`); `Login`, `RefreshSession` (ротация); `PollTelemetry`, `PollCommands` (выдать `queued` команды устройства и пометить доставленными), `AuthenticateDevice` (Basic + кэш, реш. 213), `MarkStalePollers` (реш. 214).
- [x] Ошибки — `sealed DomainError` бэкенда (`NotFound`, `Conflict`, `Validation(field, code)`, `Unauthorized`, `TokenExpired`, `TokenUsed`, `BrokerUnavailable`…), результат — `Outcome`.

### 2.2 `server:data`
- [x] Flyway `V1__core.sql` по модели `1-coursework.md` 4.4 (+ `refresh_tokens`, `device_sessions`, уникальность `telemetry(device_id, ts)` — реш. 205; индексы по `device_id, ts`; `commands(device_id, status)`), Exposed DSL-таблицы и репозитории, `ExposedTransactionRunner`, HikariCP.
- [x] `HiveMqIngest`: shared subscription `$share/backend/rf/v1/dev/+/{status|telemetry|probe|events|shadow/reported|cmd/res}`, `manualAcknowledgement`, Channel → пакет до 500 шт / 500 мс → use cases → **ack после commit** (P10); `Clean Start=false`, стабильный clientId бэкенда, Session Expiry 24 ч; парсинг через `shared:protocol` + мапперы DTO ↔ domain; нечитаемое сообщение — ack + счётчик + лог без payload.
- [x] `HiveMqPublisher` (`desired` retained QoS 1; очистка retained — пустой payload; `cmd/req` с `Response Topic`, `Correlation Data`, `Message Expiry Interval`).
- [x] `DynSecBrokerAccounts` через `$CONTROL/dynamic-security/v1` (createClient с `clientid`, addClientRole `device`, disableClient, deleteClient; ответ `$CONTROL/dynamic-security/v1/response` с таймаутом); учётка бэкенда — отдельный DynSec-клиент `backend` с ролью, разрешающей shared-подписку на `rf/v1/dev/+/…`, publish `desired`/`cmd/req` и `$CONTROL` (создаётся в `init-dynsec.sh`/`dynsec-dev.sh`).
- [x] `BcryptHasher` (cost 10), `SecureRandomSecrets`, `SystemClock`, `UuidIdGenerator` (cmdId `c-<ulid/uuid>`), `Sha256`.
- [x] Интеграционные тесты (Testcontainers): PostgreSQL — все репозитории и миграции; Mosquitto с DynSec (поднять конфиг из `deploy/mosquitto`, CA/сертификаты генерировать в тесте во временный каталог или использовать TCP без TLS **только в тестовом конфиге**, оговорить) — DynSec create/disable, ingest с ack после commit (перезапуск ingest без commit → передоставка), отозванная учётка не подключается, публикация в чужой топик отбрасывается.

### 2.3 `server:api`
- [x] Routes `/api/v1`: `POST /auth/login`, `POST /auth/refresh`; `GET/POST /restaurants`, `GET/PATCH /restaurants/{id}`; `GET /devices?restaurant=&role=&status=`, `GET /devices/{id}`, `DELETE /devices/{id}` (вывод из эксплуатации) или `POST /devices/{id}:decommission`; `GET /devices/{id}/telemetry?from=&to=&metric=`, `/probes?from=&to=&target=`, `/events`; `GET/PUT /devices/{id}/shadow` (ответ: desired, reported, delta); `POST /devices/{id}/commands`, `GET /commands/{cmdId}`; `POST /provisioning/tokens`, `POST /provisioning/tokens:batch`, `POST /provision` (без JWT, rate limit); `POST /poll/telemetry`, `GET /poll/commands`, `POST /poll/commands/result` (Basic устройства); `GET /health` (БД + брокер).
- [x] Request/response DTO API (отдельно от MQTT-DTO), StatusPages (`DomainError` → 400/401/404/409/410/503, тело `{code, message}`), JWT (access 15 мин, issuer/audience из конфига), RateLimit на `/auth/login` и `/provision`, CallLogging без `Authorization`, проверка принадлежности в polling (устройство видит только свои команды).
- [x] OpenAPI `documentation.yaml` + Swagger UI на `/swagger` (реш. 212); тест «каждый маршрут описан в спецификации».
- [x] Тесты `testApplication` с фейковыми use cases — все эндпоинты, коды ошибок, 401 без токена.

### 2.4 `server:app` и стенд
- [x] `Main.kt`: `embeddedServer(Netty)`, `install(Koin) { modules(domainModule, dataModule, apiModule, appModule) }`, hoplite `AppConfig` (env + `application.yaml`), CLI: `serve` (по умолчанию), `migrate`, `seed --restaurants deploy/seed/restaurants.yaml --users deploy/seed/users.yaml` (через use cases, upsert по `code`). Фоновые задачи: `ExpireCommands` (каждые 5 с), `MarkStalePollers` (каждые 5 с). `KoinVerify`-тест.
- [x] `deploy/seed/restaurants.yaml` (≈ 40 ресторанов, вымышленные: код, название, город, адрес, часовой пояс) и `users.yaml` (admin; пароль — из `RF_ADMIN_PASSWORD`/файла `deploy/out/`, не в git).
- [x] `deploy/docker-compose.yml`: `postgres` (timescale-pg17, volume, healthcheck), `backend` (образ из `server/app` — Dockerfile на `eclipse-temurin:21-jre` + `installDist`, порт `127.0.0.1:8080`), `migrate`, `seed` (profile `dev`), `toxiproxy` (+ `deploy/toxiproxy.json`: `api` → `apk-host:443`, `mqtt` → `mosquitto:8883`, `rest` → `backend:8080`), nginx `apk-host` проксирует `/api/`, `/health`, `/swagger` на `backend:8080` (реш. 211). `deploy/.env.example` с новыми переменными. `docker compose up` поднимает стенд одной командой (+ `deploy/README.md`).
- [x] DynSec: учётка `backend` (роль `backend-service`) в `init-dynsec.sh` и `dynsec-dev.sh`; пароль — `deploy/out/`, не argv. *(КМ2a: только `dynsec-dev.sh` — офлайн `init-dynsec.sh` ролей не создаёт, реш. 231; роли — `dynsec-roles.sh`)*
- [x] Smoke: `deploy/test-backend.sh` — health, login, создать ресторан, выпустить код, provision, `mosquitto_pub` телеметрии этой учёткой → `GET /devices/{id}` online + телеметрия в БД; RED → GREEN. *(КМ2b: 18/18; итоги и контракт для КМ3/КМ4 — `K2.md`)*

**Приёмка КМ2:** ✅ (11.11, `K2.md`) гейт rc=0; Testcontainers-сценарии К3 из `1-coursework.md` (provision → telemetry → БД; команда офлайн-устройству → доставка / `expired`; отозванная учётка не подключается); `docker compose up` + `test-backend.sh` зелёные.

---

## КМ3. Агент — обычный режим (≈ 4 дня)

Отчёт: `docs/reports/1-coursework/K3.md`. Не переделывать сделанное в MVP-A (handoff, таблица «Что из К уже сделано»).

- [ ] **3.1 `ApplyShadow` на `shared:shadow`** (валидация и решения по версии из общего модуля; поведение и тесты MVP сохраняются).
- [ ] **3.2 `RunProbes` + `probe_state`** (FR-AG-34, 44): `ProbeLoop` через `ProbeStateMachine` из `shared:probes`; событие `probe_state` {target, state, error, failures} немедленно при смене состояния; пробы офлайн буферизуются (FR-AG-35, уже есть — проверить).
- [ ] **3.3 `EvaluateThresholds`** (FR-AG-40, 41, реш. 202): пороги из тени; события `threshold` (вход/выход, гистерезис), `fg_changed`, `fg_absent` (отслеживаемое приложение не на переднем плане дольше `fgAbsentMin`); вызов из цикла телеметрии после `CollectTelemetry`; состояние порогов — в памяти (после перезапуска первое пересечение шлётся заново — оговорка). Исправить KDoc `DesiredShadow`.
- [ ] **3.4 Досылка без 60-секундной задержки** (реш. 203) и **дедупликация `package_changed`** (реш. 204), **допуск часов 5 мин** для `expiresAt` (реш. 206).
- [ ] **3.5 `Provision`** (FR-AG-10, 12): порт `ProvisioningApi` (domain) → `OkHttpProvisioningApi` (data; `POST {baseUrl}/api/v1/provision`, `shared:protocol` REST DTO, пиннинг CA стенда в debug как у MQTT, в release — системное доверие); сохранение учётки в `KeystoreCredentialStore` + host/port брокера + `role` + `restaurantId`; ошибки: неверный/использованный/просроченный код, нет сети, TLS. Debug-ресивер `DevConfigReceiver` остаётся; добавить debug-интент `DEV_PROVISION` (код + baseUrl через adb, защищён `DUMP`) для автоматизации S1.
- [ ] **3.6 Presentation (MVI по канону, Compose):** **мастер настройки** (FR-AG-04 базово, FR-AG-10): шаги — разрешения (уведомления, usage access, исключение из оптимизации батареи; переиспользовать `AndroidWizardPermissions`), адрес стенда + код регистрации → `Provision` → результат (deviceId, роль, ресторан); **экран статуса** (FR-AG-05): подключение (состояние сессии, последний `connId`, причина ошибки), режим (normal/DO), выданные разрешения, размер outbox, последние результаты проб (up/down), версия агента. Навигация: без учётки — мастер, с учёткой — статус; Capability Lab доступен из статуса (debug). Тесты Reducer/Mapper/ViewModel ≥ 85 %.
- [ ] **3.7 `status.role`** из сохранённой учётки (реш. 210).
- [ ] **3.8** Гейт, K3.md, handoff. Ручных проверок на эмуляторе внутри вехи нет — всё в валидации этапа.

**Приёмка КМ3:** гейт rc=0; domain агента ≥ 90 %; release без debug-поверхности (`verifyReleaseNoDebugSurface`).

---

## КМ4. Симулятор и эксперимент (≈ 3 дня)

Отчёт: `docs/reports/1-coursework/K4.md`; результаты — `docs/experiments/`.

- [ ] **4.1 `server:simulator`** (JVM, `application`; пакеты `domain`/`data`/`app` внутри модуля, архитектура разд. 10): регистрация через `POST /provisioning/tokens:batch` → `/provision` для каждого виртуального устройства, кэш учёток в файле (`deploy/out/sim-creds-<run>.json`, 600, вне git); распределение по существующим ресторанам (на ресторан: 2 кухни, 3 зала, 1 сборка — настраивается); профили `kitchen` (на зарядке, нагрев у плиты в часы пик), `hall` (разряд за смену), `assembly` — генераторы чистые и детерминированные от seed (тесты генераторов); MQTT-режим (HiveMQ, общий event loop, разгон с jitter, LWT/`connId`, тень, команды → `received` + итог, пробы через `shared:probes`), REST-polling режим (`POST /poll/telemetry` каждые 30 с, `GET /poll/commands` каждые 5/15/30 с, Basic). Сценарии — YAML (kaml), результаты — CSV.
- [ ] **4.2 Измеритель эксперимента** (в симуляторе, режим `experiment`): отправка команд через REST API бэкенда (admin JWT) случайным устройствам с частотой k/мин, задержка команды = время получения устройством − время отправки (один процесс, одни часы), p50/p95; трафик на устройство — по `docker stats` NET I/O контейнера симулятора (если симулятор в контейнере) или по счётчику байт на уровне сокета; CPU/ОЗУ backend/mosquitto/postgres — сэмплер `docker stats --no-stream` раз в 5 с → CSV; время обнаружения пропажи — оборвать K устройств без DISCONNECT и опрашивать `GET /devices/{id}` до `offline`.
- [ ] **4.3 Прогон:** N = 100 / 1 000 / 5 000; MQTT и REST (опрос 5 / 15 / 30 с); длительность прогона — сокращённая (≈ 3–5 мин после разгона, экстраполяция на сутки — оговорка, реш. 209); деградация на N = 1 000: Toxiproxy latency 300 мс (перед брокером и бэкендом) и потери 5 % (`tc netem` в контейнере симулятора, `cap_add: NET_ADMIN`). Если 5 000 упирается в ресурсы машины — зафиксировать и оставить 1 000 как обязательный минимум (приёмка: N ≥ 1 000).
- [ ] **4.4 Обработка:** скрипт (`tools/experiments/analyze.py`, `python3 -I`, matplotlib если есть, иначе установить в venv в scratch) → таблицы (`docs/experiments/results.md`) и графики PNG (`docs/experiments/img/*.png`); выводы.
- [ ] **4.5** Гейт, K4.md, handoff.

**Приёмка КМ4:** эксперимент проведён на N ≥ 1 000 в обоих режимах; CSV, таблицы, графики в `docs/experiments/`.

---

## КМ5. Ревью этапа, валидация, исправления

- [ ] **5.1 Ревью этапа** (один агент opus): весь diff `main..stage/1-coursework`; фокус — безопасность бэкенда (аутентификация, BOLA, rate limit, секреты в логах, SQL), протокол (P1–P10), ack после commit, Clean Architecture, тесты. Находки Critical/Important/Minor → `docs/reports/1-coursework/review.md`.
- [ ] **5.2 Валидация** на стенде + эмуляторе `rf-api36` (сценарий — `swarm-report/coursework-e2e-scenario.md`): **S1** (Swagger: ресторан → код → мастер агента → `online` в `GET /devices`), **S2′** (сеть эмулятора off → `offline` ≈ 50 с → on → outbox дослан), **S4** (`PUT shadow` интервал → `reported` применён → `delta` пуста), **S5** (команда офлайн-устройству → доставка при подключении; просроченная → `expired`), **S7′** (Toxiproxy выключает `api` → `probe_state: down` в БД). Скриншоты Swagger/агента → `docs/reports/1-coursework/img/`. Глобальные правила валидации «на проде» в К не применимы: прода нет, стенд локальный (D14) — Ruling.
- [ ] **5.3 Один раунд исправлений** (Critical + Important), гейт; повторное ревью только при Critical по безопасности.

---

## КМ6. Пояснительная записка (docx) и закрытие этапа

- [ ] **6.1 Текст записки** по ТЗ 16.3 (Введение; 1 Теоретическая часть; 2 Проектирование; 3 Реализация; 4 Экспериментальная часть; Заключение; Список источников; Приложения) — исходник Markdown в `docs/coursework/` (по файлу на главу), сырьё — отчёты `docs/reports/0-mvp/*`, `docs/reports/1-coursework/*`, ТЗ, архитектура, `docs/experiments/`. Объём ≈ 35–45 страниц. Рисунки: схема системы, дерево топиков, слои Clean Architecture, ER-модель, последовательности (провижининг, команда), графики эксперимента, скриншоты.
- [ ] **6.2 Сборка `.docx`** (скилл `docx`): титульный лист (поля-заглушки), содержание, нумерация страниц, ГОСТ-оформление (реш. 217), подписи рисунков и таблиц, список источников по ГОСТ Р 7.0.100. Результат — `docs/coursework/RestoFleet-coursework.docx` + скрипт сборки. Проверка: открыть/конвертировать в PDF (LibreOffice, если есть) и просмотреть страницы.
- [ ] **6.3 Закрытие этапа:** отчёт этапа `docs/reports/1-coursework/README.md` (итоги, метрики, сценарии, отклонения, что не проверено), `docs/stages/1-coursework.md` — `[x]` и «Передаётся в практику» фактическим состоянием, ТЗ — правки по Rulings (7.2 `threshold`, 7.5 OQ-1/OQ-2 закрыты), `docs/handoff.md`, отчёт `swarm-report/coursework-2026-11-11.md`. Слияние в `main` и тег `coursework` — пользователь.
