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

## Итог потока B (ПМ6–ПМ8)

**Коммиты ветки `stage/2-practice-console`:** `1e1609f` (ПМ6: core, root, вход, приложения), `1e87ddf` (ПМ7), `36c2923` (ПМ8), далее слияние `stage/2-practice` (контракт после ПМ2 и ПМ3, часть 1) и финальная правка. Гейт `./gradlew build detekt koverVerifyCi :konsist-tests:test --continue` — rc=0 перед каждым коммитом (холодный ≈ 7 мин, тёплый 40 с…6 мин).

**Модули (51):** `core:{common, mvi, decompose, network, storage, designsystem, ui, testing}`, `root`, `app-android`, `app-desktop`, 10 фич × 4 модуля (`auth`, `dashboard`, `restaurants`, `devices`, `device-details`, `shadow`, `commands`, `alerts`, `incidents`, `provisioning`).

**Тесты:** 264 теста консоли (JUnit 4, Truth, Turbine, MockK, `MockEngine` и фикстуры контракта, `runComposeUiTest`), 0 упавших. Konsist (`ConsoleArchitectureTest`): домен без фреймворков, `-ui` без `-data`/Decompose/Ktor/Koin, ViewModel зависит только от use cases, нет импортов чужой фичи и зависимостей фича→фича в `build.gradle.kts`, Koin только в composition root, все 10 фич на месте; проверено мутацией (чужой тип в конструкторе ViewModel, импорт Ktor и чужой фичи → красный).

**Покрытие строк (Kover, вариант `ci`, jvm):**

| Слой | Покрытие | Порог |
|---|---|---|
| `-domain` (10 модулей) | 99,3 % | ≥ 90 % |
| `-ui` (ViewModel, Reducer, Mapper; Composable исключены) | 98,5 % | ≥ 85 % |
| `-data` | 98,4 % | ≥ 80 % |
| `core:network` | 92,6 % | ≥ 80 % |
| `core:storage` | 100 % (без адаптеров Keystore и DataStore, реш. 470) | ≥ 80 % |
| `core:common`, `mvi`, `decompose`, `designsystem` | 100 % | — |
| `root` | 97,1 % | в агрегате |
| `-component` | 71,3 % (тонкая обвязка Decompose; в агрегате) | в агрегате |
| `core:ui` | 51,7 % (графики и жесты — Composable, исключены фильтром; чистые функции покрыты) | в агрегате |
| Проект целиком | 96,46 % | ≥ 80 % |

**FR-CN (реш. 401, полный объём):**

| FR | Фича | Статус |
|---|---|---|
| CN-01 | `auth`: вход, хранение refresh в Keystore (Android) / в памяти (Desktop), single-flight refresh, выход, адрес только `https` | сделано |
| CN-02 | `dashboard`: счётчики, рестораны с проблемами, последние события, обновление по `summary_changed`/`device_status`/`alert` | сделано |
| CN-03 | `restaurants`: список, поиск, переход к устройствам ресторана (только просмотр) | сделано |
| CN-04 | `devices`: фильтры «Проблемы · Ресторан · Роль · Версия · Статус», поиск `q`, пагинация, строка как в макете | сделано (фильтр версии — по версии агента, реш. 424) |
| CN-05 | `device-details`: вкладки Состояние · Проверки · События · Тень, график температуры за 30 мин + live, водопад фаз, шторка «Команда» | сделано |
| CN-06 | `shadow`: desired/reported/delta на месте, форма и режим JSON, проверка `validateDesired` до `PUT` | сделано |
| CN-07 | `commands`: ping, show_message, collect_diagnostics, launch_app; reboot и DO-команды выключены с объяснением; история; опрос статуса 2 с / 60 с | сделано |
| CN-08 | `alerts`: вкладки, свайпы, диалог с обязательным комментарием, метка «авто», обновления по `alert` | сделано |
| CN-09 | `provisioning`: код с обратным отсчётом, копирование, подсказка `adb`, стирание кода | сделано |
| CN-10 | `incidents`: список, «Почему так решили» по `evidence` для каждого вида, подтверждение с комментарием, обновления по `incident` | сделано (закрытые за 24 ч, реш. 488) |

Desktop — заглушка по реш. 422: окно 420×860 с тем же корнем, `--screenshot` рисует PNG вне экрана; раскладка «список + карточка» — диплом. Урезаний по реш. 401 не было.

**Расхождения контракта с кодом сервера:** не найдено. Сервер на момент ПМ6–ПМ8 реализован частично (ПМ2, часть ПМ3); операции с `x-planned` проверены только на `MockEngine` и фикстурах — сверка на живом стенде в валидации.

**Что проверить в валидации (ПМ11, один прогон):**
- вход на стенд по `https` с CA стенда (debug-сборка) и вход после перезапуска (Keystore, реш. 470), выход, отказ refresh (сессия кончилась → экран входа);
- WebSocket через nginx стенда: заголовок `Authorization` проходит, 4401 → refresh и переподключение, 1013 → повторное чтение (реш. 471);
- обзор: счётчики совпадают с сервером, тревога из симулятора меняет их без перезапуска; переход в карточку устройства;
- карточка: график температуры и live-точка, водопад проверок, события постранично; правка тени формой (интервал, порог, приложения) и JSON; `delta` исчезает после применения;
- команды: `ping` → `succeeded` с длительностями; `reboot` выключен в обычном режиме;
- тревоги: свайп вправо — подтверждение, свайп влево — диалог с комментарием; метка «авто»; инцидент R-08 вместо россыпи тревог и «Почему так решили»;
- провижининг: код, обратный отсчёт, копирование, команда `adb` регистрирует агента на эмуляторе; код нигде не виден после ухода с экрана (в `adb logcat` его нет, реш. 490);
- снимки экрана консоли для главы 4 отчёта (эмулятор `rf-api36`, `-port 5600`); Desktop: `./gradlew :console:app-desktop:run --args="--screenshot out.png"`.

**Не проверено:** сборка `release` и `verifyReleaseNoDebugSurface` (подпись не настроена); работа на реальных устройствах и в тёмной теме; Keystore и WebSocket на живом сервере; визуальное соответствие макету `#p` (макет сверялся по разметке, а не по снимкам); iOS-таргета нет (реш. 429).

**Для оркестратора.** Ветку можно сливать: `git merge --no-ff stage/2-practice-console` в основной копии, когда A между вехами; общие файлы правились только в разрешённых местах (`console/modules.settings.gradle.kts`, блоки `# Консоль` в toml не менялись — версии не добавлялись, блок `// console` корневого `build.gradle.kts`, `ConsoleArchitectureTest.kt`).
