# Поток A — бэкенд, симулятор, агент (этап П)

Исполнитель — поток A (реш. 406), основная рабочая копия, ветка `stage/2-practice`. План — `docs/superpowers/plans/2026-11-22-practice.md`, вехи ПМ1–ПМ5. Решения потока — `decisions.md`, раздел «Поток A» (430–469).

## Прогресс

Раздел ведётся по ходу работы, чтобы после обрыва API можно было продолжить с последнего пункта.

- 22.11 — ПМ1 начат. Прочитаны план, Rulings 400–429, спецификация, `openapi.yaml`, DTO `server:api`, `shared:protocol`. Версии зависимостей сверены с Maven Central и Google Maven.
- 22.11 — 1.1–1.3 в рабочей копии (не закоммичено): DTO консоли в `shared:protocol` `rest.console` (+ `rest.ApiErrorDto`), `server:api` их импортирует; WS — `rest.console.ws`; REST- и WS-фикстуры; `openapi.yaml` с `x-planned`; тесты `ConsoleRestDtoTest`, `WsMessagesTest`, `ConsoleContractTest`, `OpenApiSpecTest` зелёные. Дальше — 1.4 (toml, settings, корневой Kover), гейт, коммит.
- 22.11 — 1.4: toml (версии реш. 431, проверены временным спайком — удалён), `settings.gradle.kts` + `console/modules.settings.gradle.kts`, блок `// console` в корневом Kover, Konsist `serverApiDeclaresNoRestDtoOfItsOwn`; Rulings 430–442. Гейт №1 — rc=1: detekt `MatchingDeclarationName` (`ShadowViewDto.kt` → `ShadowDto.kt`) и Kover `shared:protocol` 78 % < 90 % (геттеры DTO не читались, у 6 DTO не было фикстур) — добавлены 9 фикстур и тест «поле → свойство», покрытие модуля 99,7 %. Гейт №2 — rc=0 (2 мин 26 с), коммит ПМ1.

## ПМ1. Контракт API и зависимости

**Итог.** Контракт консоли зафиксирован; изменения — только через Ruling потока A (реш. 408).

| Что | Где |
|---|---|
| REST DTO консоли (перенесены из `server:api` и новые: тревоги, инциденты, сводка, logout) | `shared/protocol/src/commonMain/kotlin/ru/restofleet/shared/protocol/rest/console/` |
| Общая ошибка API | `shared/protocol/.../rest/ApiErrorDto.kt` |
| WebSocket: конверт, типы, коды закрытия, лимиты | `shared/protocol/.../rest/console/ws/WsMessages.kt` |
| Пути консоли | `rest/console/ConsolePaths.kt` |
| OpenAPI 1.1.0-practice (`x-planned` — 9 операций и 3 параметра) | `docs/openapi.yaml` = `server/api/src/main/resources/openapi/documentation.yaml` |
| Фикстуры: 29 REST, 9 WS | `docs/protocol/fixtures/rest-console-*.json`, `ws-*.json` |
| Версии консоли и `ktor-server-websockets` | `gradle/libs.versions.toml`, блоки «П — бэкенд» и «Консоль (поток B)» |
| Список модулей консоли (владелец — B) | `console/modules.settings.gradle.kts` |

**Тесты (TDD: сначала красные — нет классов, нет схем и путей в спецификации).**
- `shared:protocol`: `FixtureRoundTripTest` (все фикстуры каталога, каждый WS-тип, ни одной WS-фикстуры в `Unknown`, каждое поле фикстуры консоли доходит до свойства DTO), `ConsoleRestDtoTest` (секреты не в `toString`, умолчания, коды, пути), `WsMessagesTest` (конверт, `Unknown` при новом `type` и `v`, клиент без `ts`, битый `data`).
- `server:api`: `OpenApiSpecTest` (сверка без `x-planned`; помеченная операция ещё не в Ktor; все операции П есть), `ConsoleContractTest` (38 схем = DTO, типы WS, пути, TTL).
- Konsist: `serverApiDeclaresNoRestDtoOfItsOwn` (проверено мутацией: `@Serializable` в `server:api` → rc=1).

**Метрики:** гейт rc=0; 1 897 тестов (было ≈ 1 870), 0 упавших; покрытие строк проекта 96,46 %, `shared:protocol` 99,7 %.

**Поведение API не менялось:** JSON на проводе прежний, новые поля `DeviceDto` необязательны и не пишутся.

**Отклонения от плана:** версии новее таблицы плана (CMP 1.12.2 вместо 1.10.x) — реш. 431; состав и форма части DTO уточнены — реш. 434–437, 439; отчёт — `P-backend.md` (реш. 430).

**Не проверено:** сборка модулей консоли целиком (спайк 6.0 потока B) и Android-ресурсы Compose (`components-resources`) в модуле-приложении; артефакты Desktop кроме macOS arm64 — только наличием POM.

**Что важно потоку B.**
- Импорт DTO: `ru.restofleet.shared.protocol.rest.console.*`, WS — `...rest.console.ws.*`, JSON — `ru.restofleet.shared.protocol.RfJson` (для WS обязателен формат JSON).
- Операции `x-planned` появятся в ПМ2 (`/alerts`, `/summary`, `/devices/{id}/commands`, параметры `q`, `version`, `problems`, поля консоли в `DeviceDto`) и ПМ3 (`/incidents`, `/auth/logout`, `/ws`); до этого — `MockEngine` и фикстуры.
- WS: токен только в заголовке `Authorization` upgrade-запроса; 4401 → refresh и переподключение, 1013 → переподключение и перечитать REST, 1008 — не переподключаться сразу; после каждого подключения — перечитать состояние.
- Тексты (объяснение инцидента, строка проблемного ресторана, строка тревоги) строит консоль по `details` и `evidence`.
- toml: свои строки — только в блоки «# Консоль (поток B)»; Kover — только внутри блока `// console` корневого `build.gradle.kts`.
- 22.11 — ПМ1 закоммичен (`53be7e9`), оркестратор влил поток C (`677bb21`), поток B стартовал. Начат ПМ2: домен `alert/` — факты-условия (`alert_conditions`, реш. 443), `RuleEngine` (чистая функция), `AlertProcessor` в транзакции приёма, порт `EventBus`.
- 22.11 — ПМ2 в рабочей копии: домен `alert/` (`RuleEngine` 37 строк таблицы, `AlertProcessor`, ack/resolve, `GetSummary`/`SummaryBuilder`), `realtime/EventBus`, `incident/ProbeStateRepository`; `IngestBatch`/`HandleStatus` зовут правила в транзакции; `V2__alerts.sql` (alerts, alert_conditions, probe_states), репозитории Exposed, `SharedFlowEventBus`, сигналы событий в декодере; API `GET /alerts`, `PATCH /alerts/{id}`, `GET /summary`, `GET /devices/{id}/commands`, `GET /devices?q=&version=&problems=`; `x-planned` сняты с них. Rulings 443–449. Тесты модулей зелёные; запущен гейт.
- 22.11 — ПМ2: гейт №1 rc=1 (Koin тестов без `RuleParams`, тест миграций ждал V1, detekt: сложность `HandleStatus`, имя файла, `TooManyFunctions`), №2 rc=1 (Konsist: вложенный класс в пакете `usecase`), №3 — rc=0. Коммит ПМ2.

## ПМ2. Тревоги R-01…R-06

**Итог.** Правила работают в транзакции приёма (реш. 414): `IngestBatch` и `HandleStatus` вызывают `AlertProcessor`, события шины уходят после commit.

| Что | Где |
|---|---|
| Модель, порты, `RuleEngine`, `AlertProcessor`, `SummaryBuilder` | `server/domain/.../alert/` |
| Use cases `ListAlerts`, `AcknowledgeAlert`, `ResolveAlert`, `GetSummary`, `ListDeviceCommands`; `ListDevices`/`GetDevice` с полями консоли | `alert/usecase/`, `command/usecase/`, `device/usecase/` |
| Порт шины, состояние проб для агрегатора | `realtime/EventBus.kt`, `incident/ProbeStateRepository.kt` |
| Миграция | `server/data/src/main/resources/db/migration/V2__alerts.sql` |
| Репозитории, шина | `data/db/ExposedAlertRepositories.kt`, `data/realtime/SharedFlowEventBus.kt` |
| Сигналы событий для правил | `data/ingest/DeviceMessageDecoder.kt` (`DeviceSignal`) |
| API | `server/api/.../alert/AlertRoutes.kt`, `AlertMappers.kt`; `GET /devices/{id}/commands`; `GET /devices?q=&version=&problems=` |

**Тесты.** `RuleEngineTest` — таблица из 37 строк (R-01…R-06, границы 499/500/599/600 МБ, повторы, ручное закрытие без повторного открытия, подтверждённая и закрытая тревога); `AlertUseCasesTest` (повторная доставка без дублей, вход и выход в одной пачке, сбой commit — ни записи, ни событий, R-01 по `status`, чужой `connId`, жизненный цикл ack → resolve, 409/400/404); `SummaryBuilderTest`; `DeviceConsoleTest`; Testcontainers: `AlertRepositoriesTest`, `IngestProcessorOnPostgresTest` (телеметрия `freeMb=400` → R-06 в БД и `AlertChanged` в шине — приёмка плана), `DeviceMessageDecoderTest`; `AlertRoutesTest` (401, 400, 404, 409, маппинг DTO).

**Метрики:** гейт rc=0; 1 946 тестов, 0 упавших; строки проекта 96,7 %, пакеты `server.domain.alert`, `alert.usecase`, `realtime`, `server.api.alert`, `server.data.realtime` — 100 %.

**Отклонения:** автор действия — `login`, а не `sub` (реш. 446); шина считает потерю новых событий, а не вытеснение старых (реш. 445); `ackBy` в DTO описан соответственно.

**Не проверено:** на стенде не запускалось (валидация — ПМ11); поведение при > 1 000 незакрытых тревог в сводке — только по коду (реш. 449).
- 22.11 — ПМ2 закоммичен (`65e026a`). Место: 12 ГБ — удалён кэш `~/.gradle/caches/9.6.1/transforms` (12 ГБ, Gradle 9.6.1 проектом не используется, последнее обращение 02.10; кэш восстанавливаемый), свободно 32 ГБ. ПМ3: сессии (семейство, потолок 7 сут, простой 24 ч, logout, `support` в seed) — домен и API готовы, тесты зелёные; начат домен инцидентов.
- 22.11 — ПМ3, часть 1 в рабочей копии: сессии (3.4), домен инцидентов (агрегатор, классификатор — 19 строк таблицы, сверка, подавление, `EvaluateIncidents` под `pg_try_advisory_xact_lock`), `V3__incidents_sessions.sql`, репозитории, тик в `serve`, API `/incidents`, `/auth/logout`. Rulings 450–454. Модульные тесты зелёные; запущен гейт для промежуточного коммита. Дальше — WebSocket (3.5) и стенд (3.6).
- 22.11 — ПМ3 часть 1 закоммичена (`fdb40e7`). Часть 2: WebSocket `/api/v1/ws` (`RealtimeHub`, `realtimeRoute`, `x-planned` снят), тесты на Netty + OkHttp (реш. 455), nginx `location = /api/v1/ws` (реш. 456), `test-backend.sh` — ротация, повтор погашенного, logout, upgrade без токена. Rulings 455–457. Запущен гейт.
- 22.11 — ПМ3 часть 2: гейт №1 rc=1 (detekt: `ReturnCount` в разборе кадров WS, длина строки) → №2 rc=0. Коммит.

## ПМ3. Инциденты, WebSocket, сессии

**Итог.** Тик `EvaluateIncidents` каждые 30 с под `pg_try_advisory_xact_lock`: агрегатор → классификатор → сверка с открытыми инцидентами → пересчёт подавления; сессии с семейством и потолком; WebSocket `/api/v1/ws` с хабом рассылки. Все операции контракта ПМ1 реализованы, `x-planned` в `openapi.yaml` не осталось.

| Что | Где |
|---|---|
| Агрегатор, классификатор, сверка, подавление, настройки | `server/domain/.../incident/` |
| Use cases: тик, список, карточка, подтверждение | `incident/usecase/` |
| Сессии: семейство, потолок, logout | `auth/SessionIssuer.kt`, `auth/usecase/Logout.kt`, `RefreshSession.kt` |
| Миграция | `V3__incidents_sessions.sql` (incidents, probe_agg_1m, FK тревог, `refresh_tokens.family_id`/`session_expires_at`, `probe_states.error`) |
| Репозитории, блокировка тика | `data/db/ExposedIncidentRepositories.kt`, `ExposedFleetQueries` |
| API | `api/incident/IncidentRoutes.kt`, `POST /auth/logout`, `api/ws/` (`RealtimeHub`, `realtimeRoute`) |
| Стенд | `deploy/nginx/apk-host.conf` (`location = /api/v1/ws`, журнал без query), `deploy/test-backend.sh` (ротация, повтор, logout, upgrade без токена), `deploy/seed/users.yaml` (`support`) |

**Тесты.** `IncidentClassifierTest` — таблица 19 строк (S8 а/б/в, P = 49/50/51 %, |A| = 2, 3 при N = 12 и 9, 10 при N = 40, одно устройство, 50 % офлайн из двух, `ref` у части, нет данных по `api`, нет данных по `ref`, сеть не входит в A) и агрегатор; `IncidentLifecycleTest` («локально → глобально → локально» с переносом дочерней тревоги, закрытие на втором тике, возврат условия в гистерезисе, R-07 подавляет R-01 и R-05, блокировка, молчащие устройства, события, выдача и подтверждение, сводка); `SessionLifetimeTest` (потолок, простой, logout, повтор); Testcontainers `IncidentsOnPostgresTest` (S8 а/б/в на данных через `IngestBatch`, `probe_agg_1m`, взаимоисключение `pg_try_advisory_xact_lock`); `IncidentRoutesTest`, `LogoutRouteTest`; `RealtimeTest` на Netty + OkHttp (401 без заголовка и с токеном в query, `hello`, телеметрия только подписчику, `summary_changed`, 4401 по фейковым часам, 1013, пятое соединение и шестая подписка — 1008, мусорные кадры — 1008, `resync` при потерях шины).

**Метрики:** гейт rc=0; 1 983 теста, 0 упавших; строки проекта 96,85 %; `server.domain.incident` 97,5 %, `incident.usecase` 100 %, `auth` 100 %, `auth.usecase` 98,5 %, `api.ws` 99,1 %, `api.incident` 100 %.

**Отклонения:** состав и доказательства инцидента — `jsonb` (реш. 452); тесты WS — на реальном сервере (реш. 455); повтор токена после выхода — не кража (реш. 451).

**Не проверено:** `test-backend.sh` с новыми шагами и nginx-проксирование WS на живом стенде — проверены `bash -n` и `nginx -t` (до загрузки сертификата); прогон — в ПМ4 (smoke s8-a) и ПМ11.
