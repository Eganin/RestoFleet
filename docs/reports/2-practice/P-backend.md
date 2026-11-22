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
