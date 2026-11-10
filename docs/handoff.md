# Handoff — точка входа для любой сессии

> Новая сессия или агент: прочитай этот файл, затем `CLAUDE.md`, план текущего этапа (ссылка ниже) и только свой раздел в нём. Блок «Текущее состояние» перезаписывается после каждой задачи, «Журнал» — дополняется.

## Текущее состояние

- **Этап:** MVP-A закрыт (эмуляторы); следующий — MVP-B / курсовая · **ветка:** `stage/0-mvp`
- **План:** `docs/superpowers/plans/2026-11-05-mvp-a.md`
- **Последняя завершённая задача:** 4.4 — ТЗ v0.3, закрытие MVP-A (отчёты `docs/reports/0-mvp/README.md`); слияния в `main` и тега `mvp-a` нет — решение пользователя
- **Раунд исправлений M3:** закрыт, перепроверен в M4.
- **Следующая задача:** MVP-B (реальные устройства, API 37, 29/31/33/35) и/или курсовая — `docs/stages/1-coursework.md`
- **Блокеры:** GitHub-remote не настроен (CI заработает после пуша)
- **Как поднять стенд:** `deploy/README.md` (4 команды + `./test-stand.sh`); нужен `local.properties` с `sdk.dir` для сборки.
- **Исполнение:** M0 — в основной сессии; M1–M4 — субагенты (исполнитель + ревьюер на задачу).

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
