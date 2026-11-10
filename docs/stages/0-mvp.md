# Этап 0 — MVP: проверка осуществимости

> **Для новой сессии.** Прочитай `CLAUDE.md`, затем `docs/architecture.md` (разд. 1, 3, 4, 6, 7, 9), затем этот файл и `docs/stages/0-mvp-feasibility.md`. Макеты: `docs/design/restofleet-blueprint.html#mvp`. Предыдущих этапов нет — начинаем с пустого репозитория.

**Длительность:** ≈ 1,5 активной недели (MVP-A) + 1–2 дня на реальных устройствах позже (MVP-B).

---

## 1. Цель и результат

Доказать до курсовой, что **каждое считывание агента и каждое действие Device Owner** работает на целевых версиях Android, а цепочка «агент → MQTT → брокер» ведёт себя как в протоколе. Результат — заполненная матрица `0-mvp-feasibility.md` и **ТЗ v0.3**.

Код MVP **не одноразовый**: Gradle-каркас, `shared:kernel`, черновик `shared:protocol` и все четыре модуля агента сразу пишутся по Clean Architecture с тестами и переходят в курсовую. Одноразовые — только серверные спайки (симулятор 10k, DynSec 10k).

## 2. Два захода

| Заход | Когда | На чём | Блокирует |
|---|---|---|---|
| **MVP-A** | сейчас | эмуляторы API 26 / 29 / 31 / 33 / 35 / 36 / актуальный; эмуляторы 26 и 36 в режиме DO | старт курсовой |
| **MVP-B** | когда появятся устройства | планшет Android 8.0 (сброс → DO), телефон Android 12 (обычный режим) | старт Д1 (Device Owner в дипломе) |

## 3. Объём

**Входит** (полный список проверок — в `0-mvp-feasibility.md`):
- Жизненный цикл агента L1–L5: FGS `specialUse`, автозапуск после перезагрузки, сторож WorkManager, разрешения, Keystore.
- Считывания R1–R14: батарея, тепловое состояние, сеть, SSID, ресурсы, версии приложений, приложение на экране, датчики, HardwarePropertiesManager, серийный номер, смена сети.
- Проверки доступности P1–P4: классы ошибок, фазы DNS/TCP/TLS/TTFB, шлюз локальной сети, работа без брокера.
- Команды обычного режима C1–C4.
- Device Owner D1–D7: установка, reboot, тихая установка, киоск с Chrome, запуск из фона, самовыдача разрешений, QR (опционально).
- MQTT M1–M7 без бэкенда.
- Серверные спайки S1–S3, ресурсы агента RS1–RS2.

**Не входит:** бэкенд, консоль, БД, правила, симулятор как продукт (только спайк).

## 4. Архитектура этапа

```
restofleet/
├─ settings.gradle.kts, build.gradle.kts (plugins apply false + Kover), gradle/libs.versions.toml, config/detekt/detekt.yml
├─ shared/kernel            DomainError, Outcome, Clock, IdGenerator
├─ shared/protocol          черновик DTO: status, telemetry, probe, events, cmd/req, cmd/res + фикстуры
├─ agent/domain             сущности снимков (BatteryState, ThermalState, NetworkState …), порты:
│                           TelemetrySource, ProbeExecutor, MessagePublisher, DeviceAdmin, CapabilityProbe;
│                           use cases: CollectTelemetry, RunCapabilityCheck, ExecuteDeviceAdminAction
├─ agent/data               collectors/ (по классу на R1–R14), probes/ (OkHttp + EventListener),
│                           mqtt/ (HiveMQ: TLS, LWT, MQTT 5 свойства), deviceadmin/ (DPM, PackageInstaller,
│                           lock task), platform/ (чистые функции маппинга сырых значений Android → домен)
├─ agent/presentation       Capability Lab (MVI): список проверок, детали, экран Device Owner
├─ agent/app                Application, AgentService (FGS), BootReceiver, WatchdogWorker, AdminReceiver, Koin
├─ server/simulator         СПАЙК: N HiveMQ-клиентов на общем event loop, замер heap/CPU
└─ deploy/                  compose: Mosquitto 2.1 (TLS + DynSec + persistence), certs/make-ca.sh
```

**Capability Lab** (`agent:presentation`) — экран, где каждое считывание и DO-действие из матрицы показано строкой: ID, статус ✅/⚠️/❌, сырые значения, кнопка «отправить в MQTT». Кнопка «Экспорт» сохраняет JSON-отчёт для этой версии Android (`adb pull`), по нему заполняется столбец матрицы. После MVP экран остаётся отладочным.

**Ключевой принцип для тестов:** код, работающий с Android API, делится на тонкий вызов (`context.registerReceiver(...)`) и чистую функцию маппинга (`BatteryReading.from(level, scale, status, plugged, tempTenths)`). Чистые функции покрыты unit-тестами; тонкий слой — Robolectric, где возможно, и Capability Lab на эмуляторе.

**Сертификаты стенда:** SAN — `localhost`, `10.0.2.2`, IP машины в локальной сети (для реальных устройств в MVP-B), `mosquitto`.

## 5. Задачи

### M0. Каркас (1–2 дня)
- [x] Монорепо без convention-плагинов: `settings.gradle.kts`, корневой `build.gradle.kts`, `libs.versions.toml` (версии, `android-compileSdk/minSdk=26/targetSdk`, `jvmTarget`), detekt, Konsist.
- [x] CI (GitHub Actions): сборка, unit-тесты, Kover-отчёт с гейтом.
- [x] `shared:kernel`, черновик `shared:protocol` + golden-фикстуры + round-trip тесты.
- [x] `deploy/`: Mosquitto TLS + DynSec + persistence; скрипт CA.

### M1. Агент: все считывания (2–3 дня)
- [x] `agent:domain`: сущности, порты, `CollectTelemetry`, `RunCapabilityCheck` — тесты первыми.
- [x] `agent:data`: коллекторы R1–R14, проверки P1–P4; чистые функции маппинга с unit-тестами.
- [x] `agent:app`: FGS `specialUse`, BOOT_COMPLETED, сторож.
- [x] `agent:presentation`: Capability Lab (MVI, тесты Reducer/Mapper/ViewModel).

### M2. Device Owner и команды (1–2 дня)
- [x] `AdminReceiver`, `deviceadmin/`: D1–D6; команды C1–C4.
- [x] Экран Device Owner в Capability Lab (только debug-сборка, решение 140).

### M3. MQTT и серверные спайки (1–2 дня)
- [x] Агент ↔ Mosquitto: M1–M7 (команды через `mosquitto_pub -D publish response-topic … -D publish correlation-data … -D publish message-expiry-interval 300`, просмотр — MQTT Explorer).
- [x] Спайк S1: симулятор 1 000 → 10 000 клиентов.
- [x] Спайки S2–S3: DynSec на 10 000 клиентов, `disableClient`.

### M4. Прогон MVP-A и ТЗ v0.3 (1 день)
- [x] Прогон матрицы на эмуляторах: API 26 и 36 (оговорка: 29/31/33/35, DO-эмуляторы кроме 36 и ночной L1 — MVP-B; API 37 — эмулятор не стартует).
- [x] Решения ✅ / ⚠️ / ❌; доменная `Capabilities` — новых правил не потребовалось (тесты с ID строк матрицы добавлены).
- [x] **ТЗ v0.3:** стек (Kotlin, Koin, Clean Architecture), этапы MVP/К/П/Д, правки протокола P1–P10, матрица 10.2, рестораны без CSV-импорта.

### M5. MVP-B — реальные устройства (позже, 1–2 дня)
- [ ] Телефон Android 12: R1–R14, P1–P4, C1–C4, L1–L5, ночной прогон.
- [ ] Планшет Android 8.0: то же + сброс → `dpm set-device-owner` → D1–D7, R10, R13.
- [ ] Дополнить матрицу и ТЗ.

## 6. Тесты

- Domain агента и `shared:*` — ≥ 90 % (Kover).
- Чистые функции маппинга `agent:data/platform` — 100 % веток.
- Capability Lab — Reducer, UiStateMapper, ViewModel.
- Протокол — round-trip всех фикстур.
- Koin — `verify()` модулей агента; Konsist — правило слоёв.

## 7. Приёмка

- MVP-A: в матрице для всех эмуляторов нет ⏳; по каждому ⚠️/❌ принято решение; ТЗ v0.3 согласовано; CI зелёный с Kover-гейтом.
- MVP-B: столбцы «Планш. 8.0» и «Тел. 12» заполнены до старта Д1.

## 8. Передаётся в курсовую

Каркас сборки, CI, `shared:kernel`, черновик `shared:protocol`, модули агента с коллекторами/проверками/MQTT-сессией/DO-адаптерами, Capability Lab, compose с Mosquitto + DynSec, `tools/emulators` (создание AVD и прогон матрицы), результаты `docs/mvp-results/`, ТЗ v0.3 и итоги MVP-A (`docs/reports/0-mvp/README.md`).

Решения по состоянию на закрытие MVP-A: симулятор — **Kotlin/JVM** (S1 на 1 000 клиентов пройден, 10 000 не проверялись); HiveMQ MQTT Client работает на Android 8; `Capabilities` не менялась (ни одна ⚠️/❌ не потребовала новых правил). Не передаётся как выполненное: API 29/31/33/35/37, ночной L1, ручные проверки L3/L4/C1–C4/D2–D5 на 26, D7 (QR), реальные устройства — это MVP-B; RS1/RS2 — ❌ на debug-сборке, release-замер в MVP-B.

## 9. Риски и открытые вопросы

- Package name приложений официанта и сборщика неизвестны — в MVP проверяем `<queries>` на Chrome и тестовом пакете; реальные — П1.
- Версии Android на устройствах ресторанов неизвестны — если там 8.0, thermal status недоступен (R-03 только по температуре батареи). Уточнить в П1.
- Если спайк S1 провален — симулятор на Go до старта курсовой (+2–3 дня).
