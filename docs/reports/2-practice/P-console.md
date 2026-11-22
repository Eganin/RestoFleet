# Поток B — Android-консоль (этап П)

Исполнитель — поток B (реш. 406), worktree `../RestoFleet-wt-console`, ветка `stage/2-practice-console`. План — `docs/superpowers/plans/2026-11-22-practice.md`, вехи ПМ6–ПМ8. Решения потока — `decisions.md`, раздел «Поток B» (470–499).

## Прогресс

Раздел ведётся по ходу работы, чтобы после обрыва API можно было продолжить с последнего пункта.

- 22.11 — старт от коммита ПМ1 (`53be7e9`). Прочитаны план, Rulings 400–442, `P-backend.md`, DTO `shared:protocol.rest.console`, макеты `#p`.
- 22.11 — **6.0 спайк** зелёный: `core:designsystem` (CMP 1.12.2, material3 1.9.0, AGP 9.3.1 `android.kotlin.multiplatform.library`, android + jvm), `jvmTest` с `runComposeUiTest`, `assemble`. Плагин `org.jetbrains.compose` в библиотеках не нужен — достаточно `kotlin-compose` и явных координат из toml; плагин остаётся `apply false` в корне.
- 22.11 — `core:{common, mvi (CoroutineExceptionHandler), testing, decompose, storage, network, designsystem, ui}` с тестами; `auth` (4 модуля); `console:root` (RootComponent, MainComponent, DI, заглушки экранов); `app-android` (`assembleDebug` зелёный); `app-desktop` (`--screenshot` отдаёт PNG экрана входа); `ConsoleArchitectureTest` (проверен мутацией: импорт Ktor и чужой фичи, чужой тип в конструкторе ViewModel → красный).

## ПМ6. Core, root, приложения, вход

- [x] 6.0 спайк; [x] 6.1 `core:*`; [x] 6.2 `auth`; [x] 6.3 `root`; [x] 6.4 `app-android`; [x] 6.5 `app-desktop`; [x] 6.6 `ConsoleArchitectureTest`, Kover-блок, гейт и коммит.

**Метрики ПМ6.** Первый гейт — rc=1 только по detekt (длинные строки в `ConsoleArchitectureTest`, `Screenshot.kt`, имя файла `ConsoleModules.kt`); исправлено, повторный гейт — см. коммит. Холодный гейт целиком ≈ 7 мин (поток A параллельно). Тесты консоли: `core:common` 4, `core:mvi` +1, `core:storage` 8, `core:network` 24, `core:decompose` 4, `core:ui` 5, `core:designsystem` 2, `auth` 17, `root` 10, `app-desktop` 4 (включая PNG экрана входа).

**Отклонения от плана ПМ6.** `expect/actual SecretCipher` заменён интерфейсом с реализацией только на android (реш. 470); движок Ktor передаёт composition root (реш. 473); плагин Ktor `Auth` заменён перехватчиком `HttpSend` (реш. 474); `core:testing` и приложения не входят в Kover-агрегат (реш. 475).

**Не проверено.** Keystore на устройстве и WebSocket через реальный сервер (реш. 470, 471) — в валидации ПМ11; `assembleRelease` и проверка `verifyReleaseNoDebugSurface` не запускались (release-подпись не настроена, этап не требует).

## ПМ7. Обзор, рестораны, устройства, карточка, тень, команды

- 22.11 — шесть фич по 4 модуля (`dashboard`, `restaurants`, `devices`, `device-details`, `shadow`, `commands`) с тестами use cases, репозиториев (`MockEngine` + фикстуры контракта), Reducer, Mapper, ViewModel; `RuleTexts` в `core:common`; `ScreenFactories` в `console:root` связывает `Output` фич с навигацией; тест корня собирает каждый экран из графа Koin.
- Решения 480–487 (detekt консоли, тексты правил, время в UI, склейка realtime, слоты вкладок, команды, форма тени, порог графика).
- [x] 7.1 … 7.6; [x] 7.7 гейт rc=0, коммит `1e87ddf`.

## ПМ8. Тревоги, инциденты, провижининг

- 22.11 — `alerts` (вкладки, свайпы, диалог с обязательным комментарием, метка «авто», обновления по WS `alert`), `incidents` (список; карточка с «Почему так решили» — `IncidentExplanationMapper`, табличный тест на все виды; подтверждение с комментарием), `provisioning` (код с обратным отсчётом, копирование, подсказка `adb`, стирание кода), `AlertsHostComponent` (переключатель «Тревоги | Инциденты») и связка экранов в `ScreenFactories`.
- Решения 488–491 (сутки вместо «сегодня», переключатель в `root`, подсказка `adb`, повторное подтверждение).
- [x] 8.1, [x] 8.2, [x] 8.3, [x] 8.4 (гейт и коммит — ниже). Все 10 фич собраны в `app-android` (`assembleDebug` зелёный).
