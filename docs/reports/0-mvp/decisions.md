# Журнал решений — этап MVP

Формат: дата · контекст · решение · альтернативы · последствия. Новые записи — в конец.

## 2026-11-06 · Решения при согласовании плана MVP-A

План: `docs/superpowers/plans/2026-11-05-mvp-a.md`.

| # | Контекст | Решение | Альтернативы | Последствия |
|---|---|---|---|---|
| 1 | detekt 1.23.8 (стабильный) собран под Kotlin 2.0 и не поддерживает 2.4 | detekt **2.0.0-alpha.6** (поддержка Kotlin 2.4 — с alpha.4) | откатить Kotlin на 2.0.x | alpha может ломаться между релизами; при поломке detekt отключается в CI отдельным коммитом, Kotlin не откатываем |
| 2 | `compileSdk` / `targetSdk` | `compileSdk 37` (константы разрешения на локальную сеть для P3), `targetSdk 36` (ТЗ), `minSdk 26` | compileSdk 36 | P3 на API 37 проверяется при targetSdk 36; переход на 37 потребует разрешения на локальную сеть |
| 3 | Имя типа результата в `shared:kernel` | `Outcome<T, E>` | `Result` | не затеняет `kotlin.Result` |
| 4 | Где канон MVI | `console:core:mvi` создаётся уже в MVP; агент подключает его | отдельный MVI в агенте | канон один на консоль и агента (архитектура разд. 5) |
| 5 | Где архитектурные тесты | отдельный JVM-модуль `:konsist-tests` | тесты в продуктовом модуле | Konsist читает исходники с диска, продуктовые модули не зависят от него |
| 6 | Спайки S1–S3 | одноразовый модуль `:spikes:broker-load`, исключён из Kover | `server/simulator` | настоящий симулятор пишется в К с нуля по Clean Architecture |
| 7 | Чем проверять R7/R12/C2/D3 | тестовое приложение `:tools:dummy-waiter` + nginx `apk-host` (TLS стенда) | чужие APK | контролируемые версии и подпись |
| 8 | Моки в KMP | Mokkery в MVP не подключаем: ручные фейки портов; MockK — только Android-модули | Mokkery | меньше зависимостей, привязанных к версии Kotlin |
| 9 | Как передать учётку MQTT на эмулятор | `adb shell am broadcast` в debug-ресивер с `android:permission="android.permission.DUMP"`; хранение через Keystore (L5) | экран ввода | обычные приложения не могут отправить этот broadcast |
| 10 | Повторная доставка команд QoS 1 | исполненные `cmdId` (последние 200) хранятся персистентно; повтор игнорируется | только в памяти | защищает от цикла `reboot` после перезагрузки |
| 11 | Истёкшая команда (`expiresAt < now`) | агент не исполняет, отвечает `failed`, `reason: "expired"` | исполнить | уточнение P2 со стороны агента; вносится в ТЗ v0.3 |
| 12 | Тесты (по требованию пользователя) | JUnit 4 + Google Truth во всех модулях; тесты KMP-модулей — в `src/jvmTest` (Truth только JVM) | `kotlin.test` в `commonTest` | один стиль ассертов с white-label-android |
| 13 | Git (по требованию пользователя) | ветка на этап: `stage/0-mvp`, `stage/1-coursework`, `stage/2-practice`, `stage/3-diploma`; `main` — документация и завершённые этапы | одна ветка | правило вносится в ТЗ v0.3 (задача 4.4) |
| 14 | Device Owner (по требованию пользователя) | DO проверяется на эмуляторах API 26/35/36/37; D7 (QR) — попытка на образе с мастером настройки | только реальный планшет | на MVP-B остаётся только то, что на эмуляторе невозможно |

## 2026-11-06 · Исполнение M0

| # | Контекст | Решение | Альтернативы | Последствия |
|---|---|---|---|---|
| 15 | Kover не покрывал DTO `agent_lifecycle` и `package_changed` (для них не было фикстур), а строки первичных конструкторов DTO не исполняются при декодировании | Добавлены 2 фикстуры (итого 15) и контрактный тест `MinimalDtoEncodingTest` (минимальный DTO кодируется без `null`, значения по умолчанию пишутся явно); порог 90 % сохранён | снизить порог `shared:protocol` до 80 % (DTO — слой data) | мутация `explicitNulls = true` роняет 7 тестов — контракт канонического вида защищён |
| 16 | `CommandStatusDto` | константы enum в UPPER_CASE с `@SerialName` в нижнем регистре (в плане были имена в нижнем регистре) | lower-case имена констант | соответствует стилю Kotlin/detekt; на проводе — `received`/`succeeded`/… |
| 17 | detekt 2 в KMP: задача `detekt` пустая (NO-SOURCE), анализ идёт в `detekt<SourceSet>SourceSet` | в каждом KMP-модуле `detekt` зависит от своих source-set задач; параметр `LongParameterList` в detekt 2 — `allowedConstructorParameters` | корневая задача-агрегатор | 3 строки в каждом KMP-модуле; CI вызывает `detekt` |
| 18 | Kover 0.9.11: агрегация KMP | собственный вариант `ci` в каждом модуле (KMP — `add("jvm")`, Android-модули в M1 — `add("debug")`), в корне — пустой `createVariant("ci")` и правило ≥ 80 %; задачи `koverVerifyCi` / `koverHtmlReportCi` | вариант total | Android-модули обязаны объявить вариант `ci`, иначе выпадут из гейта |
| 19 | Версии GitHub Actions (06.11.2026) | checkout@v7, setup-java@v6, gradle/actions/setup-gradle@v6, upload-artifact@v7 | — | первый прогон — после пуша на GitHub |
| 20 | Ключ сервера Mosquitto в контейнере читает uid 1883 | `server.key` с правами 644 в `deploy/certs/out` (вне git); ключ CA — 600 | chown на uid 1883 на хосте (требует sudo) | локальный стенд; в Д (VPS) — секреты через docker secrets |
| 21 | Smoke-тест стенда | `deploy/test-stand.sh` проверяет отказ CONNACK «not authorised», а не отсутствие сообщений | проверка таймаутом подписки | мутация `allow_anonymous true` ловится |

## 2026-11-06 · Исправления по итоговому ревью M0

| # | Контекст | Решение | Альтернативы | Последствия |
|---|---|---|---|---|
| 22 | `jvmTarget = 17` из toml не применялся: байткод был Java 21 (major 65) — Android-модули не смогли бы инлайнить `Outcome.map` | `compilerOptions.jvmTarget` из каталога для `android` и `jvm` таргетов; тест `BytecodeTargetTest` проверяет major 61 | перейти на 21 везде | каждый новый модуль обязан задать `jvmTarget` из toml |
| 23 | `DeviceId` жил в `shared:protocol` (слой data) — domain работал бы с сырой строкой | `DeviceId` перенесён в `shared:kernel`; `shared:protocol` зависит от kernel через `api` | оставить в протоколе | `BrokerSettings.deviceId: DeviceId` (план, задача 3.5) |
| 24 | Konsist-правила: домен по зашитому списку путей, пустой набор проходил, протокол не запрещён в домене, Koin и presentation проверялись слишком узко | слой определяется путём модуля (regex), проверка непустого domain-набора, в домене запрещены `shared.protocol` и `.data.`, вне composition root запрещён любой `org.koin.*` и наследование `KoinComponent`; `tools/dev/konsist-violations.sh` — набор нарушений для проверки правил | проверки в Gradle по графу модулей | правила проверены: старые пропускали 4 нарушения, новые ловят все |
| 25 | В брокер монтировался весь `certs/out`, включая `ca.key`; пароль admin (полный доступ ко всем топикам) был в окружении брокера и в healthcheck. **Заменяет решение 20** | брокеру монтируется только `certs/out/broker/` (без ключа CA), ключ сервера копируется внутрь с владельцем `mosquitto` и правами 600 (на хосте тоже 600); healthcheck — анонимный CONNACK «not authorised»; пароль admin — только у одноразового `dynsec-init` | отдельный health-клиент DynSec | `test-stand.sh` проверяет отсутствие `ca.key` и пароля в контейнере |
| 26 | detekt `MatchingDeclarationName` на `Resources.kt` (в файле только `DeviceResources`); `MagicNumber` на `0..100` | файл оставлен по брифу, `@file:Suppress("MatchingDeclarationName")` с комментарием; диапазон вынесен в `private val LEVEL_RANGE` | переименовать файл в `DeviceResources.kt` | имена файлов заданы брифом 1.2 (дальнейшие задачи могут на них ссылаться) |
| 27 | Review 1.3: `catch (Throwable)` поглощал `Error` (OOM, StackOverflow) — агент продолжал бы слать «все датчики Failed» | ловится `Exception` (CancellationException пробрасывается), `Error` пробрасывается; тест `errorIsNotSwallowed`; `@Suppress("TooGenericExceptionCaught")` оставлен — detekt ругается и на `Exception`, цель класса — изоляция любого исключения считывания | ловить конкретные типы (список платформенных исключений неизвестен в domain) | тест `errorIsNotSwallowed` |
| 28 | Бриф 1.5 требовал `ksp`/`room`/`serialization` и koin в `agent:data`; Kover 0.9 не принимает фильтры в правиле (только в варианте) | в `agent:data` только `android-library`, kover, detekt (Kotlin встроен в AGP 9, расширение `kotlin { compilerOptions }`); ksp/room — в 3.3; 100 % ветвей `platform` — отдельный вариант Kover `platform` с фильтром пакета, `koverVerifyCi` модуля зависит от `koverVerifyPlatform`; `when` по плотным константам заменён на Map (tableswitch давал ложные непокрытые ветви) | правило на весь модуль | `koverVerifyPlatform` падал на 92,86 % до правки |
| 29 | 1.6: в манифесте `agent:data` добавлены ACCESS_COARSE_LOCATION (lint CoarseFineLocation при FINE) и ACCESS_FINE_LOCATION (R5 SSID) сверх списка контроллера | добавлены оба | без разрешения SSID всегда `<unknown ssid>` | lint зелёный |
| 30 | 1.6: R12/R14 в Lab — `SubscriptionCheck` (подписка стартует при создании списка проверок, OK = подписка жива, события в raw) | отдельный класс вместо `ReadingCheck` | ReadingCheck с одноразовым чтением | тест `SubscriptionCheckTest` |
| 31 | 1.6 (обновлено в fix round 1): `PackageEventsObserver` — RECEIVER_NOT_EXPORTED на API 33+, ниже RECEIVER_EXPORTED (выбор по `ApiLevel`) | ContextCompat при NOT_EXPORTED на API < 33 требует собственное разрешение (тест падал); ACTION_PACKAGE_* — защищённые системные броадкасты | NOT_EXPORTED | `ObserversTest` |
| 32 | 1.6: kover-вариант `ci` модуля исключает пакет `..data.di` | как в корневом агрегаторе (constraints: `di/` исключены); без этого ChecksModule давал 76 % | тест Koin-модуля | модуль 96,7 % строк |
| 33 | 1.6 fix 1: `isEmulator(hardware, fingerprint)` — ranchu/goldfish, fingerprint начинается с `generic` (с учётом регистра) или содержит `/sdk_gphone` / `sdk_google` | ruling контроллера: подстроки `generic`/`emu` давали ложные срабатывания на реальных планшетах и GSI | формула брифа | `EmulatorDetectionTest` (9 кейсов) |
| 34 | 1.6 fix 1: `SubscriptionCheck` — OK только при живой подписке (после старта collect); отмена/завершение/ошибка -> UNAVAILABLE, не стартовала за 2 с -> NOT_CHECKED | ложный OK при мёртвой подписке | ждать старт без таймаута | `SubscriptionCheckTest` |
| 35 | 1.6 fix 1: логика проверок вынесена из `di` в `collectors/CapabilityChecksFactory` + `CheckValues`; R11 при нехватке датчика отдаёт `Failed("missing sensor: …")` (а не безликий NoSensor); регистрация слушателя света отклонена -> отдельное Failed | исключение `di` из Kover теперь прячет только проводку | оставить логику в модуле | `CapabilityChecksFactory*Test` |
