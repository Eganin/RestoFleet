# RestoFleet

IoT-платформа мониторинга и удалённого управления Android-устройствами ресторанной сети. Курсовая → практика → ВКР.

## Где что лежит
- `TZ_RestoFleet.md` — ТЗ (v0.2; правки v0.3 — по итогам MVP).
- `docs/architecture.md` — общая архитектура для всех этапов.
- `docs/plan.md` — дорожная карта этапов.
- `docs/stages/0-mvp.md`, `0-mvp-feasibility.md`, `1-coursework.md`, `2-practice.md`, `3-diploma.md` — один файл на этап, каждый самодостаточен.
- `docs/design/restofleet-blueprint.html` — макеты экранов и схемы (якоря `#mvp`, `#k`, `#p`, `#d`).

## Как начинать сессию
Пользователь называет этап → прочитать `docs/architecture.md` и файл этапа (в его шапке указаны нужные разделы) → работать только в объёме этапа.

## Обязательные правила
- Весь код на Kotlin: агент (Android), консоль (Compose Multiplatform: Android + Desktop), бэкенд (Ktor), симулятор (JVM).
- **Clean Architecture везде**: domain / data / presentation; domain без Android, Ktor, Exposed, сериализации, Koin.
- **Unit-тесты на большую часть кода**, TDD; Kover-гейт: проект ≥ 80 %, domain ≥ 90 %.
- **Koin** — DI на всех частях, включая Ktor-бэкенд; только constructor injection.
- **Без Gradle convention-плагинов и build-logic**: свой `build.gradle.kts` у модуля, все версии и SDK-уровни — в `gradle/libs.versions.toml`.
- Presentation — MVI по канону `white-label-android` (`docs/architecture.md` разд. 5), навигация консоли — Decompose.
- Рестораны: CRUD API + dev-seed из YAML для стенда; в UI — добавление и правка в Desktop-консоли (диплом). Импорта из CSV нет.
- Требования, получившие ❌ в матрице MVP, не реализуются.
