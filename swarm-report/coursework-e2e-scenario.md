# E2E Scenario: валидация этапа «Курсовая» (КМ5, п. 5.2)

Платформы: Backend, Android (эмулятор `rf-api36`, adb `emulator-5600`).
Стенд локальный (D14, реш. 294): «деплой» = подъём стенда `docker compose --profile dev up -d --wait` + smoke `test-backend.sh`.
Устройство валидации — `$DEV` = `d-c7387657-44fa-4036-b48d-42469eadc963` (выдал `/provision` в S1.5). Итоги и дефекты — `docs/reports/1-coursework/validation.md`. Время — UTC.
Правило: перед каждым действием перечитать этот файл, продолжать с первого `[ ]`.

## Подготовка
- [x] P1. ✅ df 11 ГБ (< 12: висячих своих образов нет, prune — 0 Б); `up -d --wait` — все healthy, `migrate`/`seed` exit 0 (seed: 40 обновлено). `df -h .`; стенд: `cd deploy && docker compose --profile dev up -d --wait` → все сервисы healthy, `migrate`/`seed` exit 0.
- [x] P2. ⚠️ 1-й прогон rc=1 (FAIL 9: «устройство не online»): после рестарта mosquitto прошлым прогоном `shared_subscriptions/count = 0` → **V-1**. Обход: `docker compose restart backend` → shared 6 → 2-й прогон 18/18 rc=0; он снова рестартует брокер → shared 0 → снова рестарт бэкенда, shared 6. `./test-backend.sh` → 18/18 PASS.
- [x] P3. ✅ `assembleDebug` rc=0; SHA-256 `res/raw/stand_ca.pem` в APK = `ca.crt` стенда. `./gradlew :agent:app:assembleDebug` → APK собран, CA стенда в APK совпадает с `deploy/certs/out/public/ca.crt`.
- [x] P4. ✅ загрузка из снапшота, `boot_completed=1`; `uninstall` + `install -r -t` (debug-APK test-only: без `-t` — `INSTALL_FAILED_TEST_ONLY`, в K3.md команда без `-t` → V-3); часы эмулятора = хост (±1 с). Эмулятор `rf-api36` (`-no-window -no-audio -no-boot-anim -port 5600`) → `sys.boot_completed=1`; агент переустановлен начисто (`uninstall` + `install`) → нет учётки, открывается мастер.
- [x] P5. ✅ `playwright-cli list` — «no browsers»: сессии `restofleet` нет → Swagger UI не снимается (рисунок `swagger-ui` заменят таблицей). `playwright-cli list` → есть ли открытая сессия `restofleet` (иначе Swagger UI не снимать).

## S1. Регистрация по коду → `online`
- [x] S1.1. ✅ 200, JWT. `POST /api/v1/auth/login` (admin, пароль из файла) → 200, `accessToken`.
- [x] S1.2. ✅ 200, 45 ресторанов (40 seed + 5 `SMOKE-*` от прогонов `test-backend.sh`), выбран `MSK-01` «Северная кухня». `GET /api/v1/restaurants` → 40 ресторанов, выбран `MSK-01`.
- [x] S1.3. ✅ 201, код 26 символов, `expiresAt` = +24,0 ч. `POST /api/v1/provisioning/tokens {restaurantId, role: kitchen, name}` → 201, `code`, `expiresAt` ≈ +24 ч.
- [x] S1.4. ✅ разрешения: уведомления — диалог ALLOW; usage access и батарея — `appops`/`deviceidle` (эквивалент «Открыть» → системные настройки), статусы перечитаны «выдано». Код введён (`adb shell sh` < файл). `img/agent-wizard.png`. Мастер на эмуляторе: шаг разрешений → «Далее» → шаг адреса и кода (адрес по умолчанию `https://10.0.2.2:8443`) → ввести код → скриншот `agent-wizard.png`.
- [x] S1.5. ✅ тап 19:13:29,8 → `/provision` 19:13:30,46 → «Шаг 3 из 3 · Готово»: `d-c7387657-…963`, роль «кухня», ресторан — UUID (не имя, наблюдение). `img/agent-wizard-done.png`. «Зарегистрировать» → итог (устройство, роль `kitchen`, ресторан) → скриншот `agent-wizard-done.png` → переход на статус.
- [x] S1.6. ✅ «подключён», connId `k-6fdc86f7-c37`, брокер `10.0.2.2:8883`, outbox 0. Экран статуса: «подключён», `connId`, брокер `10.0.2.2:8883` (скриншот `agent-status.png` — после S7.1, когда есть проба `api`).
- [x] S1.7. ✅ `GET /devices?status=online` → 1 элемент — `$DEV` (kitchen, mqtt, «Кухня-валидация»); БД: `online`, сессия `connected_at` 19:13:31,888 (≈ 2,0 с от тапа), токен `used_at` = `created_at` устройства. `GET /api/v1/devices?status=online` → в списке `$DEV` (роль kitchen, транспорт mqtt); в БД `devices.status=online`, строка `device_sessions`, `provisioning_tokens.used_at` заполнено.
- [x] S1.8. ✅ 409 `token_used`. Негатив: тот же код повторно в `POST /api/v1/provision` → 409 `token_used`.

## S4. Тень: интервал телеметрии
- [x] S4.1. ✅ desired v1 = reported v1 (интервал 30), `delta` {}, `pending` false. `GET /devices/$DEV/shadow` → desired v1 (`intervalSec` 30), reported v1, `delta` пуста.
- [x] S4.2. ✅ PUT 19:14:30,6 → 200, desired v2, `published` true, `delta` {telemetry:{intervalSec:15}}. `PUT /devices/$DEV/shadow {telemetry:{intervalSec:15}}` → 200, desired v2, `published=true`.
- [x] S4.3. ✅ первый же опрос через 1 с: reported v2, интервал 15, `delta` {}, `errors` []; `reportedAt` 19:14:31,16 (≈ 0,55 с после PUT). ≤ 10 с: `GET shadow` → reported v2, `intervalSec` 15, `delta` пуста; в БД `shadows.reported_version=2`, `errors=[]`.
- [x] S4.4. ✅ шаг `ts` до смены 30,0 с; после — 15,0 с (первый тик по старому таймеру в 19:14:48, затем 19:15:03 и далее по 15 с). Телеметрия в БД после применения идёт с шагом ≈ 15 с (медиана интервала между `ts`).

## S7′. Проба `api` через Toxiproxy
- [x] S7.1. ⚠️ PUT probes (как в K3.md, без `reference`) → reported v3, `pending` false, но `delta` = весь список `probes`: агент эхом пишет `reference:false`, списки сравниваются целиком → **V-2**. С явным `reference:false` → v4, `delta` {}. Проба: `probe_state up` 19:14:41,7, `probe_results` ok 200, шаг ≈ 5,5 с, ≈ 530 мс. `PUT shadow {probes:[{id:api, type:http, url:https://10.0.2.2:18443/health, intervalSec:5, timeoutMs:3000, failThreshold:3}]}` → desired v3 → reported v3, `delta` пуста; в БД `probe_results` api ok=true status=200; событие `probe_state` `up`.
- [x] S7.2. ✅ `img/agent-status.png` (прокручен: подключение, устройство, разрешения, «api · доступна, HTTP 200 · 525 мс») и `img/agent-status-top.png` (верх экрана). Скриншот `agent-status.png`: подключён, устройство, проба `api` up 200.
- [x] S7.3. ✅ прокси `api` выключен 19:15:42,6 → неудачи 42,6 / 48,2 / 53,2 (`tls_error`: порт Docker Desktop принимает TCP и рвёт) → `probe_state down failures=3` 19:15:53,2 (≈ 10,6 с по часам устройства; часы эмулятора отстают ≈ 1 с). `POST toxiproxy /proxies/api {enabled:false}` в T0 → в БД событие `probe_state` `down` (failures ≥ 3) через ≈ 15–25 с после T0.
- [x] S7.4. ✅ включён 19:16:09,8 → `probe_state up` 19:16:09,8 (≈ 1 с с поправкой часов); прокси api/mqtt/rest включены, токсиков 0. `POST /proxies/api {enabled:true}` → событие `probe_state` `up`; прокси снова включены, токсиков нет.

## S5 + S2′. Офлайн: команда, просрочка, outbox
- [x] S5.1. ✅ `ping` ttl 300 → `succeeded`; `delivered_at − created_at` = 531 мс, итог через 537 мс. Базовая линия: `ping` онлайн → `succeeded`, задержка доставки (`delivered_at − created_at`).
- [x] S2.1. ✅ последняя телеметрия до отключения 19:16:33,6 seq 13; сеть выключена в T0 = 19:16:48,165. Зафиксировать последнюю телеметрию (`ts`, `seq`) → сеть эмулятора выключить (`svc wifi disable; svc data disable`) в T0.
- [x] S2.2. ✅ LWT: сессия закрыта `reason=lwt` в 19:17:29,597 — **41,4 с** после T0; API показал `offline` при опросе в 19:17:31,5 (≤ 43,3 с). Опрос `GET /devices/$DEV` раз в 2 с → `status=offline` в T_off; ожидается T_off − T0 ≈ 45–50 с (keep-alive 30 с × 1,5).
- [x] S5.2. ✅ 19:17:41: ping ttl 600 → `queued`; ping ttl 20 → `expired` в 19:18:04,3 (через 3,2 с после `expiresAt`, планировщик раз в 5 с), `delivered_at` пуст. Офлайн: `ping` с `ttlSec` 600 → `queued`; `ping` с `ttlSec` 20 → через ≤ 25 с `expired`.
- [x] S2.3. ✅ офлайн 3 мин 11 с; outbox на экране: 22 (через 85 с), 49 (перед включением); статус «подключение…». Сеть включена T1 = 19:19:59,4. Офлайн ≈ 3 мин (телеметрия копится в outbox) → сеть включить в T1.
- [x] S2.4. ⚠️ сеть есть с 19:19:58 (`net_changed`, `probe_state up` 19:20:03,8), но MQTT переподключился только в 19:21:26,27 — **87 с** после T1 (пауза backoff до 120 с, возврат сети её не прерывает) → **V-4**. Затем outbox 0. Телеметрия seq 14…31 за офлайн-окно — 18 записей, `seq` подряд без пропусков и дублей (34 записи = 34 разных seq; ingest `ignored=0`); один шаг 24 с (19:16:48→19:17:12, тик сдвинулся при потере связи, запись не потеряна). `probe_results` за окно — 51 (15 ok после возврата сети). События: `net_changed none` дважды с одним `ts` (id 6, 7) — дубль досылки событий, допустим по реш. 207. → `online`; телеметрия за [T0, T1] в БД (ожидается ≈ (T1 − T0)/15 записей, разрыв между соседними `ts` ≤ 1,5 × интервала), дублей `seq` нет; события без дублей.
- [x] S5.3. ✅ ping ttl 600 → `delivered` 19:21:26,289 (через 0,02 с после `connected_at`), `succeeded` 19:21:26,291; от создания до доставки 3 мин 45 с (всё время — офлайн). Просроченная — `expired`, на устройство не доставлена (Message Expiry брокера 20 с). Команда с `ttlSec` 600 → `delivered` → `succeeded` после подключения; задержка от подключения (`device_sessions.connected_at`) до `delivered_at`. Просроченная осталась `expired` (на устройство не исполнена).

## N. Ночной прогон, сокращённый (реш. 209, 295)
- [x] N1. ✅ 19:23:42–19:38:42: 60 записей, seq 41…100 подряд (разрывов 0), шаг 15,01–15,04 с (ср. 15,023), новых MQTT-сессий 0, пробы 162/162 ok. ≈ 15 мин связи: телеметрия без пропусков (число записей ≈ 15 × 60 / 15, макс. разрыв ≤ 1,5 × интервала, `seq` подряд).
- [x] N2. ✅ сеть выключена T0 = 19:38:53,9, LWT через 40,7 с; офлайн 10 мин 01 с, outbox перед включением 162 (телеметрия 40 + пробы 120 + события); включена T1 = 19:48:55,5 → сессия через 1,8 с (фаза backoff совпала, ср. 87 с в S2′ — V-4) → outbox 0 через ≤ 25 с. Телеметрия за окно 40 записей, seq 102…141 подряд, шаг 15,01–15,03 с; всего у устройства 144 записи = 144 разных seq = 144 разных ts; пробы 120; новых дублей событий нет; ingest `ignored=0`. ≈ 10 мин офлайна → сеть включить → outbox дослан: записи за офлайн-окно ≈ 40, без разрывов и дублей; outbox на экране статуса 0.

## Завершение
- [x] Z1. ✅ прокси api/mqtt/rest включены, токсиков 0; все сервисы healthy; файл опций admin в контейнере брокера удалён. Toxiproxy: прокси включены, токсиков 0; стенд healthy.
- [x] Z2. ✅ `emu kill`, `adb devices` пуст. Эмулятор выключен (`adb -s emulator-5600 emu kill`).
- [x] Z3. ✅ 0 совпадений по 5 021 шаблону (пароли стенда, dev-учётки, DynSec admin, 5 000 учёток симулятора) во всех своих файлах и PNG; код регистрации на `agent-wizard.png` погашен (409). Утечки: пароли/код в отчётах и скриншотах — 0 (`grep -c`).
