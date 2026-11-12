# Handoff — точка входа для любой сессии

> Новая сессия или агент: прочитай этот файл, затем `CLAUDE.md`, план текущего этапа (ссылка ниже) и только свой раздел в нём. Блок «Текущее состояние» перезаписывается после каждой задачи, «Журнал» — дополняется.

## Текущее состояние

- **Этап:** **курсовая (К) в работе** с 11.11.2026, ночной прогон без пользователя. MVP-A закрыт 10.11.2026; MVP-B (реальные устройства) идёт параллельно, когда появятся планшет и телефон: `docs/stages/0-mvp.md`, задача M5.
- **Ветка:** `stage/1-coursework` от `main` @ `c978ffe`. Первый коммит ветки `5ef2a37` — незакоммиченные файлы MVP-A (реш. 200). 11.11 пользователь переименовал `stage/0-mvp` в `main`; ветку MVP и тег `mvp-a` восстанавливает пользователь, если захочет: `git branch stage/0-mvp c978ffe && git tag -a mvp-a -m "MVP-A closed" c978ffe`.
- **План К:** `docs/superpowers/plans/2026-11-11-coursework.md` — вехи КМ1 (протокол, shadow, probes) → КМ2 (бэкенд, брокер, стенд) → КМ3 (агент) → КМ4 (симулятор, эксперимент) → КМ5 (ревью, валидация S1/S2′/S4/S5/S7′) → КМ6 (записка `.docx`, закрытие). Rulings — `docs/reports/1-coursework/decisions.md` (старт 200–217, КМ1 218–228, КМ2a 229–243, КМ2b 244–262, КМ3 263–278, КМ4 279–291, записка 292–293, КМ5-валидация 294–300, КМ5-исправления 301–313, серия 2 эксперимента 314–317), отчёты вех — `docs/reports/1-coursework/K<n>.md`.
- **Текущая веха:** **КМ5 закрыт, эксперимент перепрогнан** (12.11): 5.1 ревью (`review.md`), 5.2 валидация (`validation.md`), 5.3 раунд исправлений (`K5.md`), **серия 2 эксперимента** — все 16 прогонов с окном 600 с и входом измерителя до окна (R-4, R-5), `docs/experiments/results.md` и графики перегенерированы, серия 1 отменена (`data/runs-series1-240s.csv`; `K4.md`, раздел «Серия 2»). **Следующий шаг — КМ6 (записка):** глава 4 — из `results.md` (что брать — `K4.md`, «Что важно знать КМ6»; в черновике `docs/coursework/04-experiment.md` закрыть `[[TODO: серия 2 …]]`, рисунки `build_docx.py` берёт из `docs/experiments/img/` по тем же id), таблица 3.6 — из `validation.md`, скриншоты — `docs/coursework/img/agent-wizard.png`, `agent-status.png` (`swagger-ui` не снят, реш. 297). КМ1–КМ5: `K1.md`…`K5.md`.
- **Последняя завершённая работа:** 12.11 — **серия 2 эксперимента КМ4** (после ревью КМ5): 16/16 прогонов rc=0 за 3 ч 21 мин (9 600/9 600 команд, пропажа 160/160, обрывов 0). Итоги N = 1 000: трафик MQTT 2,64 МБ/сутки против 5,11 / 7,02 / 14,66 у REST 30 / 15 / 5 с; p50 12,3 мс против 15,70 / 7,53 / 2,41 с; CPU сервера (среднее / медиана / пик) MQTT 8,8 / 8,2 / 23,4 против REST 5 с 65,0 / 40,3 / 734,3; пропажа 41,4 с против 87,9 / 42,9 / 13,3 с. **Выводы о CPU изменились качественно:** CPU REST в 5–17 раз выше MQTT уже при N ≥ 1 000, при больших N его определяют проверки bcrypt раз в TTL кэша Basic, а не частота опроса (N = 5 000: REST 30 / 15 / 5 с — 187,6 / 198,9 / 264,8 % при MQTT 15,2 %). «Макс.» задержки MQTT 140–221 → 18–38 мс (R-4). Трафик REST +10…13 % из-за заголовков `X-RateLimit-*` (R-7, реш. 317). `analyze.py`: пик CPU, архив серии, раздел «Серия 1 (отменена)», пачечность keep-alive по журналу брокера. Rulings 314–317. Предыдущая работа — КМ5 п. 5.3 (раунд исправлений, `K5.md`).
- **Блокеры:** нет. Обход V-1 (реш. 296, рестарт бэкенда после брокера) больше не нужен: бэкенд переподписывается сам, `$SYS/broker/shared_subscriptions/count` = 6 после рестарта брокера. Пушит пользователь сам (HTTPS + токен). CI GitHub Actions заработает после пуша.
- **Как поднять стенд:** `cd deploy && ./prepare-stand.sh && docker compose --profile dev up -d --wait && ./test-backend.sh` (подробно — `deploy/README.md`). Эксперимент — `tools/experiments/run-experiment.sh` (README, «Эксперимент»); на стенде 5 000 устройств симулятора (`offline`), учётки — `deploy/out/sim/creds.jsonl` (600). Swagger — `https://localhost:8443/swagger`, `admin` / `deploy/out/admin-password`. Для сборки нужен `local.properties` с `sdk.dir`.
- **Исполнение MVP-A:** M0 — в основной сессии, M1–M4 — субагенты.

## Старт этапа К — что нужно знать новой сессии

### Порядок чтения
1. Этот раздел.
2. `CLAUDE.md`.
3. `docs/architecture.md`, разд. 1–10 и 12.
4. `docs/stages/1-coursework.md` — объём, задачи К1–К4, приёмка S1/S2′/S4/S5/S7′.
5. По ссылкам из файла этапа: `TZ_RestoFleet.md` v0.3 (разд. 7 — протокол, 13.2 — эксперимент, 16.3 — записка), `docs/stages/0-mvp-feasibility.md` (что ✅/⚠️, что не реализуется) и `docs/reports/0-mvp/decisions.md` (решения 1–187).

Обзор MVP для людей: `swarm-report/mvp-a-explainer-2026-11-11.html` и `mvp-a-use-cases-2026-11-11.html` (22 сценария).

### Что из К уже сделано в MVP-A — не переделывать, а доводить
| Пункт К | Состояние после MVP-A | Где |
|---|---|---|
| К1 `shared:protocol` | **Готово в КМ1** (11.11): финальные DTO, REST DTO (`rest/`), 39 фикстур, аудит — `K1.md` | `shared/protocol`, `docs/protocol/fixtures` |
| К1 `shared:shadow`, `shared:probes` | **Готово в КМ1** (11.11); агент на них с КМ3 (`RunProbes`, `ApplyDesiredShadow`) | `shared/shadow`, `shared/probes` |
| К2 MQTT-сессия | **Есть:**<ul><li>LWT с `connId`, `cleanStart=false`, Session Expiry 24 ч, keepalive 30 с;</li><li>повтор 1…120 с, `auth_failed` 15→60 мин, `session_taken_over` 5 мин;</li><li>пиннинг CA стенда (только debug)</li></ul> | `agent/data/.../mqtt` (`MqttBrokerSession`, `HiveMqLink`, `MqttSessionConfigFactory` в `MqttSessionConfig.kt`, `ReconnectBackoff`, `SessionSupervisor`) |
| К2 outbox на Room | **Есть.** 10 000 записей, приоритеты, пачки по 50, удаление окончательных отказов PUBACK | `agent/data/.../outbox` (Room), `agent/domain/.../usecase/PublishOrBuffer`, `FlushOutbox` |
| К2 Keystore (FR-AG-12) | **Есть.** AES-GCM; временный сбой учётку не стирает | `KeystoreCredentialStore` |
| К2 провижининг по коду (FR-AG-10) | **Готово в КМ3:** `Provision` + `OkHttpProvisioningApi`, мастер, debug `DEV_PROVISION` (adb) | `agent/*/provisioning`, `usecase/Provision` |
| К2 use cases агента | **Готово в КМ3:** + `RunProbes` (up/down), `EvaluateThresholds`, `Provision`, `run_probe`/`collect_diagnostics`, досылка по сигналу | `agent/domain` |
| К2 мастер и экран статуса | **Готово в КМ3** (MVI, навигация «нет учётки → мастер, есть → статус»); ночной прогон на эмуляторе — КМ5 | `agent/presentation/{root,setup,status}` |
| К3 брокер | **Есть.** Роль `device` с ACL по `%u` и направлениям, `clientid = deviceId`, persistence; с КМ2a — роль `backend-service` и учётка `backend` (`dynsec-roles.sh`); `test-dynsec-dev.sh` 22/22 (КМ5: пароли DynSec — файлы опций, R-10); пределы `max_packet_size`, `max_queued_*`, `memory_limit` (КМ5) | `deploy/mosquitto` |
| К3 бэкенд `server:*` | **Готово в КМ2 (11.11):** `server:domain`, `server:data`, `server:api` (REST + Swagger), `server:app` (CLI, Koin, образ); стенд в compose (`postgres`, `backend`, `migrate`, `seed`, `toxiproxy`); `test-backend.sh` 18/18. Контракт для КМ3/КМ4 — `K2.md` | `server/*`, `deploy/` |
| К4 симулятор | **Готово в КМ4** (12.11): симулятор, эксперимент на N = 100 / 1 000 / 5 000; после ревью КМ5 — серия 2 (окно 600 с), результаты — `docs/experiments/results.md`, отчёт `K4.md` | `server/simulator`, `tools/experiments`, `docs/experiments` |

### Что решить в начале К (с пользователем или через Ruling в журнале плана)
1. **Пороги (FR-AG-40/41).** По ТЗ и `1-coursework.md` 4.3 их проверяет агент (`EvaluateThresholds`, этап К). KDoc `DesiredShadow` в MVP говорит обратное: «агент сам не применяет, проверяет бэкенд». Привести код к ТЗ.
2. **Задержка событий до 60 с при живой связи** (наблюдение 11.11, DO-прогон). `PublishOrBuffer` шлёт сразу, только если outbox пуст, иначе ставит в очередь, а досылка идёт при подключении и раз в 60 с (`SessionSupervisor.DEFAULT_FLUSH_INTERVAL_MS`). Решить: досылать сразу, как только очередь опустела или пришло новое сообщение.
3. **Двойные `package_changed`.** На одно удаление или обновление приходит два события. Решить, где дедуплицировать: в агенте или в `IngestBatch`.
4. **`seq` телеметрии сбрасывается** при перезапуске агента, а `bootId` есть только в `status`. Ключ дедупликации и порядка для ingest — вместе с OQ-2 (ТЗ v0.3).
5. **OQ-1:** `expiresAt` сравнивается с часами устройства. При уходе часов вперёд больше чем на 5 мин все команды получают `expired`.
6. **Курсовая без UI консоли** — согласовать с преподавателем (риск из `1-coursework.md`). Запасной вариант — 2 экрана за ≈ 2 дня.
7. **Не проверено в MVP-A и нужно К2:** сторож L3, ночной прогон L1 (экран эмулятора не засыпает как у планшета). RS1/RS2 ⚠️ — release-замер можно сделать на эмуляторе раньше MVP-B.
8. **Матрица R12:** стоит ⚠️ («событий не было»), но в DO-прогоне 11.11 события `package_changed` пришли. Обновить при следующей правке матрицы.

### Инструменты
- **Гейт перед каждым коммитом:** `./gradlew build detekt koverVerifyCi :konsist-tests:test --continue` → rc=0. **Нужен запущенный Docker** (Testcontainers `server:data` и `server:app`: образы `timescale/timescaledb:2.31.0-pg17`, `eclipse-mosquitto:2.1.2-alpine`). Холодная сборка ≈ 7 мин, тёплая — 2–5 мин. ≈ 1 870 тестов, покрытие строк 96,3 %. Покрытие по пакетам — `./gradlew koverXmlReportCi` → `build/reports/kover/reportCi.xml` (гейт его не пересобирает). Агенту-исполнителю: гейт запускать **фоновой** командой — в «переднем» режиме инструмента тесты с Robolectric/MockK падают на self-attach ByteBuddy (окружение, не код); если KSP Room потерял `agent/data/build/generated/ksp/debug` — `./gradlew :agent:data:kspDebugKotlin --rerun`.
- **Стенд:** `deploy/README.md`. Проверки: `./test-backend.sh` (20), `./test-stand.sh`, `mosquitto/test-dynsec-dev.sh` (22/22), `certs/test-make-ca.sh`. `test-backend.sh` и `test-stand.sh` перезапускают mosquitto — агенты на эмуляторе переподключатся.
- **Эмуляторы:** `tools/emulators/run-matrix.sh rf-api36 36 [--window]` и `run-matrix.sh rf-api36-do 36 --do [--window]`. Весь MQTT-обмен прогона пишется в `logs/run-matrix/` (вне git). Подробности — `tools/emulators/README.md`.
- **Оператор вручную:** функция `O() { docker compose exec -T -e XDG_CONFIG_HOME=/tmp/rfopt mosquitto "$@"; }`. Форма `$O` в zsh (оболочка пользователя) не работает. Отправка команды одной строкой (`rfcmd`) — в `mvp-a-use-cases-2026-11-11.html`, раздел «Подготовка».
- **Версии:** Kotlin 2.4.21, **AGP 9.3.1** (Android Studio пользователя поддерживает не выше; после обновления Studio можно вернуть 9.5.x, решение 187), Gradle 9.8.2, compileSdk 37, targetSdk 36, minSdk 26.

### Правила (подробно — `CLAUDE.md`, `docs/plan.md` и память проекта)
- **Код:** весь на Kotlin, Clean Architecture (domain без Android, Ktor, Exposed, сериализации и Koin), Koin только через конструктор, в том числе в Ktor. Без convention-плагинов: версии и SDK-уровни — в `gradle/libs.versions.toml`. Presentation — MVI по канону.
- **Тесты:** TDD, JUnit 4 + **Truth**, MockK. Kover: domain и data ≥ 90 %, проект ≥ 80 %. Требования с ❌ в матрице не реализуются (в MVP-A ❌ нет).
- **Git:**
  - ветка на этап (`stage/1-coursework`);
  - коммит только после гейта rc=0;
  - **без трейлеров `Co-Authored-By` и `Generated with`**;
  - не сливать в `main`, не ставить теги, не пушить без явной просьбы.
- **Секреты:**
  - не выводить и не коммитить `deploy/.env`, `deploy/out/`, `deploy/certs/out/`, `deploy/apk/`;
  - пароли передавать только через файлы и stdin, не через argv, echo или grep;
  - проверка утечки — только числом: `grep -cFf <файл шаблонов 600>`.

  Репозиторий на GitHub публичный (`Eganin/RetroFleet`).
- **Эмулятор в сессии агента:**
  - только свои AVD `rf-api36` и `rf-api36-do`;
  - запуск только `-no-window -no-audio -no-boot-anim -port 5600`, один эмулятор одновременно;
  - чужие AVD (Kiosk_Tab_29, Kiosk_Tab_36, Medium_Phone, Pixel_4, Pixel_9a, e2e_avd) не трогать;
  - пользователь сам может запускать с окном (`--window`).
- **Окружение:** `python3 -I`; `timeout` на macOS нет; свободного места должно быть ≥ 15 ГБ.
- **Процесс** (пожелания пользователя):
  - меньше агентов и повторных проверок;
  - ревью и валидация — в конце вехи;
  - неоднозначности — через Ruling в журнале, без вопросов в ночных прогонах;
  - отчёты — сырьё для текста курсовой: обновлять этот файл и `docs/reports/1-coursework/` после каждой задачи.

### Фраза для старта новой сессии
> Работаем над этапом «курсовая». Прочитай `docs/handoff.md` (раздел «Старт этапа К»), `CLAUDE.md`, `docs/architecture.md` (разд. 1–10, 12) и `docs/stages/1-coursework.md`. Сначала спроси меня про незакоммиченные файлы, затем создай ветку `stage/1-coursework` от `main`, напиши план `docs/superpowers/plans/<дата>-coursework.md` с учётом того, что уже сделано в MVP-A, и согласуй его со мной.

## Журнал

| Дата | Задача | Коммит | Итог |
|---|---|---|---|
| 2026-11-06 | План MVP-A согласован | — | решения — `docs/reports/0-mvp/decisions.md` |
| 2026-11-06 | 0.1 git, wrapper 9.8.2, version catalog, root build | см. `git log` | `./gradlew help` зелёный, все плагины резолвятся |
| 2026-11-06 | 0.2 shared:kernel (Outcome, DomainError, Clock, IdGenerator) | см. `git log` | 12 тестов, покрытие 100 %; нужен `local.properties` с `sdk.dir` (AGP сам ставит платформу 37) |
| 2026-11-06 | 0.3 shared:protocol — DeviceId, Topics, RfJson | см. `git log` | 6 тестов; небезопасные deviceId (/ + # пробел, >64, не ASCII) отклоняются |
| 2026-11-06 | 0.4 shared:protocol — черновик DTO, 15 golden-фикстур, round-trip | см. `git log` | 23 теста протокола; покрытие ≥ 90 %; добавлены фикстуры agent_lifecycle и package_changed |
| 2026-11-06 | 0.5 detekt 2 и Konsist (:konsist-tests) | см. `git log` | detekt чистый (порог длины строки не поднимался); 4 правила Konsist ловят нарушения (проверено мутацией) |
| 2026-11-06 | 0.6 Kover-агрегатор (вариант ci) и GitHub Actions | см. `git log` | `./gradlew build detekt koverVerifyCi` зелёный (1 мин); строки 199/199; гейт проверен нарушением |
| 2026-11-06 | 0.7 стенд — CA (make-ca.sh) и Mosquitto 2.1.2 (TLS 8883 + DynSec + persistence) | см. `git log` | `deploy/test-stand.sh` и `deploy/certs/test-make-ca.sh` зелёные; мутации (anon разрешён) ловятся |
| 2026-11-06 | 0.8 закрытие M0 — отчёт `docs/reports/0-mvp/M0.md` | см. `git log` | M0 закрыт: 39 тестов, покрытие строк 100 %, стенд зелёный |
| 2026-11-06 | Итоговое ревью M0: 6 Important исправлены (решения 22–25), 10 Minor отложены | см. `git log` | 42 теста, стенд зелёный; отложенные Minor — в docs/reports/0-mvp/M0.md |
| 2026-11-06 | 1.1 console:core:mvi: MviStore, Reducer, BaseMviViewModel (10 тестов) | см. `git log` | jvmTest 10/10 зелёные, покрытие модуля 100%, полная сборка exit 0 |
| 2026-11-06 | 1.2 agent:domain: модель считываний (Reading, BatteryState и др.) и порты TelemetrySource/SequenceSource | см. `git log` | jvmTest 11 тестов зелёные, покрытие модуля 100%, полная сборка exit 0 |
| 2026-11-06 | 1.3 CollectTelemetry: изоляция сбоев считываний, Failed(message), CancellationException пробрасывается | см. `git log` | agent:domain jvmTest зелёные, покрытие инструкций 96.7%, строк 94% |
| 2026-11-06 | 1.3 fix: CollectTelemetry ловит Exception, Error пробрасывается | см. `git log` | agent:domain тесты зелёные, покрытие >=90% |
| 2026-11-06 | 1.4 Каталог проверок (43 CheckId), RunCapabilityChecks, ExportCapabilityReport | см. `git log` | agent:domain: 16 тестов в задаче, line coverage модуля 97.5%, build/detekt/kover/konsist exit 0 |
| 2026-11-06 | 1.5 agent:data/platform: чистые функции маппинга (7 файлов) | см. `git log` | тесты agent:data зелёные, ветви platform 50/50 (100%), полная сборка exit 0 |
| 2026-11-06 | 1.5 fix round 1: check зависит от koverVerifyPlatform, тест округления | см. `git log` | гейт platform проверен мутацией (98% -> FAIL), полная сборка exit 0 |
| 2026-11-06 | 1.6 agent:data/collectors: R1–R9, R11, R12, R14 + ReadingCheck/SubscriptionCheck + ChecksModule | см. `git log` | тесты agent:data зелёные (~85), строки модуля 96,7 %, ветви platform 100 %, полная сборка exit 0 |
| 2026-11-06 | 1.7 проверки доступности P1–P3: HTTPS-пробы (классы ошибок, фазы), проба шлюза, Lab-проверки | см. `git log` | agent:data строки 96,1 %, ветви platform 100 %, agent:domain строки 97,2 %, полная сборка exit 0 |
| 2026-11-06 | 1.7 fix round 1: классификатор по errno, P1 отдельной проверкой, tcpReachable, TLS-only клиент | см. `git log` | agent:data строки 96,4 %, agent:domain 97,2 %, ветви platform 100 %, полная сборка exit 0 |
| 2026-11-06 | 1.8 agent:app — FGS specialUse, автозапуск, сторож, HeartbeatLog, L1–L4 | см. `git log` | agent:data строки 97,1 %, agent:app 98,2 %, корень 98,2 %, ветви platform 100 %, полная сборка exit 0; ручная проверка на rf-api36 пройдена |
| 2026-11-06 | 1.8 fix round 1: диспетчер цикла, best-effort журнал, Koin-фабрика сторожа, L3/boot-pin и minor | см. `git log` | agent:data строки 97,2 %, agent:app 98,3 %, агрегат 98,2 %, ветви platform 100 %, полная сборка exit 0 |
| 2026-11-06 | 1.9 Capability Lab (MVI, JSON-экспорт, P1 параллельно) | см. `git log` | agent:presentation строки 98,6 %, agent:data 97,2 %, агрегат 96,3 %, ветви platform 100 %, полная сборка exit 0; ручная проверка на rf-api36: 43 записи в JSON |
| 2026-11-07 | M1: исправления по итоговому ревью вехи (I1–I7) | см. `git log` | тесты: build detekt koverVerifyCi konsist rc=0; domain 99.5%, data 97.5%, aggregate 96.3%; rf-api36: wake lock держится |
| 2026-11-07 | 1.10 закрытие M1 (отчёт M1.md, handoff) | см. `git log` | M1 закрыт: 495 тестов в проекте (453 в M1), строки 97,0 %; Lab на rf-api36: 43 записи, ok 12 / limited 3 / unavailable 3 / not_checked 25 |
| 2026-11-07 | 2.1 домен — Capabilities, команды, ExecuteCommand | см. `git log` | 27 новых тестов (11 Capabilities, 16 ExecuteCommand), agent:domain 76 тестов, строки domain 97,2 %, проект 96,9 %, полная сборка rc=0 |
| 2026-11-07 | 2.2 тестовое приложение и раздача APK | см. `git log` | dummy-waiter 4.13/4.14, apk-host nginx 1.30.6 на 8443; build detekt koverVerifyCi konsist rc=0; test-stand.sh зелёный |
| 2026-11-07 | 2.3 deviceadmin — D1–D6, R10, R13 | см. `git log` | DpmDeviceAdmin (reboot, установка APK с SHA-256 через PackageInstaller, lock task, самовыдача разрешений), R10/R13 (серийный номер маскируется), AdminReceiver, LockTaskActivity; тесты зелёные, data 97,4 %, проект 97,1 %; решения 99–105; ручная проверка DO — 2.5 |
| 2026-11-08 | 2.4 команды обычного режима C1–C4 | см. `git log` | 31 новый тест command (+2 Koin), agent:data строки 97,6 %, агрегат 97,4 %, ветви platform 100 %, полная сборка rc=0; решения 111–113: DIRECT_DO для фонового запуска в документации не подтверждён — проверить в M4 |
| 2026-11-08 | 2.4 раунд 1: исправления ревью (I1–I3, m1–m9) | см. `git log` | тесты command 48 (+17), мутанты I2/I3 убиты; решение 114 |
| 2026-11-08 | 2.5 экран Device Owner и команды в Lab | см. `git log` | тесты зелёные, 3 прогона presentation/app rc=0; покрытие строк: presentation 98,1, data 97,8, domain 98,5, app 98,1, проект 97,4; ручная проверка DO на rf-api36-do: D1-D3, D2 reboot, R10, R13 ок; D4-D6, C2-C4 с оговорками (решения 115-117) |
| 2026-11-08 | 2.5 раунд 1: исправления ревью (I1, I2, m1–m10) | см. `git log` | тесты зелёные, rerun presentation/app rc=0; покрытие строк: presentation 98,3, data 97,8, domain 98,5, app 98,1, проект 97,4; I2 проверен на rf-api36 и rf-api36-do (решения 118-119) |
| 2026-11-08 | 3.1 DynSec: роль device с %u-ACL, operator-dev, 15 dev-клиентов | см. `git log` | test-dynsec-dev.sh 16 проверок PASS (RED -> GREEN), test-stand.sh зелёный, build detekt koverVerifyCi konsist rc=0; M2 ✅; решения 120-122 |
| 2026-11-09 | 3.2 домен MQTT: порты MessagePublisher/BrokerSession/Outbox/ProcessedCommands/ShadowStore, модели, мапперы domain↔протокол по golden-фикстурам | см. `git log` | мапперы 40 тестов (+9 domain), agent:data 518 тестов, строки: data 97,8 %, domain 98,5 %, проект 97,4 %, полная сборка rc=0; решения 123-127 |
| 2026-11-09 | 3.3 хранилище: Room Outbox (10 000, FIFO по приоритету) и журнал ProcessedCommands (200), storageModule | см. `git log` | agent:data 538 тестов, строки проекта 98,1 %, 3 прогона --rerun rc=0, полная сборка rc=0; решения 128-130 |
| 2026-11-09 | 3.4 домен сессии: PublishOrBuffer, FlushOutbox, HandleIncomingCommand (at-most-once, таймауты), ApplyDesiredShadow, порт PayloadEncoder | см. `git log` | agent:domain 158 тестов (54 новых), строки domain 95,5 %, проект 96,3 %, полная сборка rc=0; мутанты: mark после execute, удаление пачкой, без Mutex убиты; решения 131-135 |
| 2026-11-09 | 3.5 учётка устройства: Keystore (L5), CredentialStore, debug-ресивер DevConfigReceiver (DUMP) | см. `git log` | agent:domain +ConfigureBrokerCredentials, 3 прогона --rerun app/data rc=0, полная сборка rc=0; строки проекта 96,1 %; L5 на rf-api36 ok до и после reboot; решения 136-139 |
| 2026-11-09 | 2.6 закрытие M2: отчёт M2.md, метрики, handoff; исправления по ревью вехи (C1, I1–I3, m1–m9, решения 140–147) | см. `git log` | 1070 тестов, строки проекта 96,2 %; rerun app/data rc=0, полная сборка rc=0; D3/киоск/reboot проверены на rf-api36-do |
| 2026-11-09 | 3.6a MQTT-сессия: `MqttBrokerSession` над `HiveMqLink`, TLS-пиннинг CA стенда (debug), LWT с `connId`, автопереподключение 1–120 с, команды/`desired` с ack после обработки, досылка outbox, телеметрия и события в MQTT, `FileShadowStore`, M1 в Lab | см. `git log` | agent:data 704 теста, всего ~1195; строки проекта 93,3 %, data 90,9 % (HiveMqLink без тестов), domain 96,1 %; полная сборка rc=0; M1 на `rf-api36`: `online`, телеметрия 30 с, LWT с тем же `connId`; решения 148–157 |
| 2026-11-09 | 3.6b `capability_check` из Lab, debug-кнопки M2/M7 (`debug_seq`), `desired` в циклы (интервал, `watchedApps`, `ProbeLoop`) | см. `git log` | решения 158–162; ручной проверки нет (п. 29), M2/M7/P4 — в 3.7; кнопки: «Публикация в чужой топик», «Сгенерировать N событий» |
| 2026-11-10 | Раунд исправлений по ревью M3 (I1–I5, M6, M7, M11, M17) | см. `git log` | решения 170–178; 1286 тестов (data 765, domain 215), строки: data 91,7 %, domain 96,5 %, проект 93,9 %; полная сборка rc=0; `test-dynsec-dev.sh` проверен только `bash -n`, живой прогон 16/16 — за контроллером |
| 2026-11-10 | 3.9, M4: закрытие M3, прогон эмуляторов 26/36 (37 не стартует), ТЗ v0.3 | см. `git log` | MVP-A закрыт: ⚠️ RS1/RS2 (debug, цели сохраняются), ⚠️ S1 на 1 000; MVP-B — остальное |
| 2026-11-11 | AGP 9.5.1 → 9.3.1 (совместимость с Android Studio) | см. `git log` | решение 187; подавление lint в `SerialCollector` перенесено на функцию; полная сборка rc=0 |
| 2026-11-11 | `run-matrix.sh`: `--window`, MQTT-лог прогона (`logs/run-matrix/`), `--do` (D3 установка и обновление, D5, D2 командами по MQTT), отказ при занятом AVD | не закоммичено | прогоны `rf-api36` и `rf-api36-do` rc=0; в DO-логе пришли `package_changed` (R12), события задерживались до 60 с при непустом outbox |
| 2026-11-11 | HTML-отчёты MVP-A (обзор, 22 сценария); `deploy/README.md`: `O()` вместо `$O` для zsh; `0-mvp.md`: RS → ⚠️ | не закоммичено | `swarm-report/mvp-a-*-2026-11-11.html` |
| 2026-11-11 | Handoff к курсовой: раздел «Старт этапа К» | не закоммичено | следующий шаг — план `docs/superpowers/plans/<дата>-coursework.md` |
| 2026-11-11 | Старт К: ветка `stage/1-coursework`, план `2026-11-11-coursework.md`, Rulings 200–217 | см. `git log` | базовый гейт на ветке rc=0 |
| 2026-11-11 | КМ1: протокол (аудит 14 расхождений, `threshold`/`fg_*`, `expectedStatus`, REST DTO, фикстуры на всё), `shared:shadow`, `shared:probes`, перенос правил из агента, Rulings 218–228 | `5802f1b`, `4c11216`, `a0a5dea` + docs | 1340 тестов, строки проекта 94,1 %, `shared:shadow`/`probes` 100 %; гейт rc=0 перед каждым кодовым коммитом; отчёт `K1.md` |
| 2026-11-11 | КМ2a: 2.2 `server:data` (репозитории, миграции, ingest, публикации, DynSec, безопасность) + тесты; учётка DynSec `backend`; Rulings 229–243 (229–240 восстановлены по коду) | `f23e836`, `6027554`, `bfeb62a` + docs | 110 тестов `server:data` (Testcontainers PostgreSQL и Mosquitto/DynSec, сценарии К3), строки 98,5 %; проект 95,6 %; исправлены: успешная публикация считалась таймаутом, потеря сообщения на границе окна пачки, серверные модули вне Kover-гейта; `test-dynsec-dev.sh` на стенде 21/21 |
| 2026-11-11 | КМ2b: 2.3 `server:api`, 2.4 `server:app` и стенд (compose: postgres, migrate, backend, seed, toxiproxy; nginx → API; `prepare-stand.sh`, `test-backend.sh`); исправлено переподключение бэкенда к брокеру (дефект КМ2a); Rulings 244–262 | `13c9c8b`, `038e59e`, `b5cc6a1`, `9322a58` + docs | КМ2 закрыт: гейт rc=0 перед каждым коммитом, `docker compose --profile dev up -d --wait` + `test-backend.sh` 18/18 (RED 502 → GREEN), утечек секретов 0; 1 618 тестов, `server:api` 98,8 %, `server:app` 93,9 %, проект 95,9 %; контракт для КМ3/КМ4 — `K2.md` |
| 2026-11-11 | КМ3: агент обычного режима — `RunProbes`/`probe_state`, `EvaluateThresholds`, `FlushTrigger`, дедупликация `package_changed`, допуск часов, `run_probe`/`collect_diagnostics`, `expectedStatus`, `Provision` + `DEV_PROVISION`, мастер и экран статуса, `status.role`, Lab только в debug; Rulings 263–278 | `709101e` + docs | КМ3 закрыт: гейт rc=0 перед кодовым коммитом, `verifyReleaseNoDebugSurface` зелёный; 1 768 тестов, domain агента 98,7 %, presentation 95,2 %, проект 96,0 %; на эмуляторе не запускалось (КМ5) — `K3.md` |
| 2026-11-12 | КМ4: `server:simulator` (профили, поведение агента, MQTT/REST-polling, YAML, CSV, измеритель), образ и сервис `simulator`, сеть `control`, оркестратор и `analyze.py`; регистрация 5 000; 16 прогонов эксперимента; Rulings 279–291 | `ccebdbe` + docs | КМ4 закрыт: гейт rc=0 перед кодовым коммитом; все прогоны rc=0 (240/240 команд, пропажа 10/10); N = 1 000: трафик MQTT 2,64 МБ/сутки против 4,60–12,99 у REST, p50 11,2 мс против 2,29–16,88 с; N = 5 000 выполнено; стенд — умолчания, `test-backend.sh` 18/18; 1 840 тестов, проект 96,3 % |
| 2026-11-12 | КМ5 5.2: валидация S1, S2′, S4, S5, S7′ и сокращённого ночного прогона К2 на стенде и `rf-api36`; скриншоты мастера и статуса; Rulings 294–300 | см. `git log` (docs) | S1/S4/S5/S7′/S2′ ✅, ночной прогон сокращён ✅ (реш. 295); V-1 Critical (ingest после рестарта брокера), V-2 Important (`delta` проб), V-3…V-5 Minor — `validation.md`; `swagger-ui` не снят (реш. 297); стенд поднят, Toxiproxy без токсиков, эмулятор выключен |
| 2026-11-12 | КМ5 5.3: раунд исправлений — V-1, V-2, R-1…R-8, R-10, R-20 (код, TDD), R-9, V-3 (документация); Minor — «Отложено в этап П»; Rulings 301–313 | `118ea87`, `dd9e211`, `49c8c29` + docs | гейт rc=0 (третий прогон: detekt и копия OpenAPI исправлены); 1 869 тестов, проект 96,3 %; стенд: `test-backend.sh` 20/20 (приём после рестарта брокера, `delta` пробы без `reference`), `test-dynsec-dev.sh` 22/22; эксперимент не перепрогонялся (R-4, R-5 — следующий исполнитель); стенд поднят, умолчания, Toxiproxy без токсиков |
| 2026-11-12 | КМ4, серия 2: перепрогон 16 прогонов эксперимента после ревью КМ5 (окно 600 с, вход измерителя до окна), `analyze.py` (пик CPU, архив серии 1, keep-alive по журналу брокера, поздние команды), `results.md`, графики, K4.md «Серия 2»; Rulings 314–317 | см. `git log` | 16/16 rc=0; CPU REST в 5–17 раз выше MQTT при N ≥ 1 000 (bcrypt раз в TTL), трафик и задержки — как в серии 1 (REST +10…13 % из-за `X-RateLimit-*`); гейт rc=0; стенд по умолчанию, `test-backend.sh` 20/20 |
