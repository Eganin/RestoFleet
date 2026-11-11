# Ревью этапа «Курсовая» (К)

- **Дата:** 2026-11-12
- **Ветка / коммит HEAD:** `stage/1-coursework` / `d0888ca7ebb5bd0d5b5ea343739d4837333ced73`
- **Diff:** `main..stage/1-coursework`: 611 файлов, +46 835 / −393 строк, 24 коммита. Без документации и тестов — около 17 тыс. строк кода.
- **Как проверялось:** только чтение кода, `git` и сырых CSV эксперимента в `docs/experiments/data/raw/`. Gradle, Docker и эмулятор не запускались, тесты не прогонялись. Каждая находка подтверждена чтением кода: указаны путь вызова и сценарий отказа. Поведение библиотек сверено по исходникам, если они есть в кэше Gradle (kotlinx-serialization 1.12.0). Для Mosquitto и PostgreSQL сверка — по документации, это оговорено в находке.

**Что просмотрено:**
- `server/domain`, `server/data`, `server/api`, `server/app`: весь код `main`. Тесты — по названиям и выборочно: ACL, безопасность API, фейковый `TransactionRunner`, ingest.
- `deploy/`: compose, `mosquitto.conf`, `dynsec-roles.sh`, `dynsec-dev.sh`, `test-dynsec-dev.sh`, nginx, `toxiproxy.json`, `prepare-stand.sh`, Dockerfile бэкенда; из `test-backend.sh` — части про секреты.
- `shared/protocol`: `Topics`, `ShadowJson`, `RfJson`, REST-DTO. `shared/shadow`: валидация. `konsist-tests`.
- `agent/*`, `server/simulator`, `tools/experiments`, `tools/emulators`: смотрели отдельные ревьюеры. Находки уровня Important я перепроверил сам по коду и сырым CSV, Minor — выборочно.

**Что не смотрел:**
- текст записки (`docs/coursework/`), `swarm-report/*.html`, `spikes/`;
- Lab и Device Owner агента (в diff они почти только переехали между пакетами), тексты `strings.xml`;
- содержимое golden-фикстур, построчно — OpenAPI-спецификацию;
- код графиков в `analyze.py`; логику DO-сценариев в `tools/emulators/run-matrix.sh`;
- поведение на работающем стенде: этим параллельно занят другой агент.

**Отброшенные гипотезы (записаны, чтобы их не перепроверять):**
- Переполнение стека от глубоко вложенного JSON в `shadow/reported` или `cmd/res`. Kotlinx-serialization 1.12.0 ограничивает вложенность сама (`maxNestingDepth = 400` по умолчанию, `JsonTreeReader.withDepthControl`) и бросает `SerializationException`. Декодер её ловит, сообщение считается нечитаемым.
- SQL-инъекции. Сырого SQL нет, только Exposed DSL с параметрами.

---

## Critical

Нет.

---

## Important

### R-1. Размер MQTT-сообщения нигде не ограничен до того, как бэкенд прочитает его целиком: одно устройство может исчерпать память брокера и бэкенда

**Где:**
- `deploy/mosquitto/mosquitto.conf:1-25`: нет ни `max_packet_size`, ни `message_size_limit`.
- `server/data/src/main/kotlin/ru/restofleet/server/data/mqtt/HiveMqClient.kt:50-52`: у клиента бэкенда задан только `receiveMaximum`, `maximumPacketSize` не задан.
- `HiveMqClient.kt:153`: `publish.payloadAsBytes` копирует payload целиком.
- `server/data/src/main/kotlin/ru/restofleet/server/data/ingest/IngestPipeline.kt:35`: очередь конвейера — `Channel.UNLIMITED`.
- `server/data/src/main/kotlin/ru/restofleet/server/data/ingest/DeviceMessageDecoder.kt:67`: предел 256 КБ проверяется только здесь.
- `server/app/Dockerfile:15`: `-XX:+ExitOnOutOfMemoryError`.

**Что не так:**
- Mosquitto по умолчанию пропускает пакет до 256 МБ (лимит MQTT). HiveMQ-клиент по умолчанию принимает столько же.
- Бэкенд получает пакет целиком, копирует payload в массив байт и держит его в неограниченной очереди до разбора пачки. Только тогда `DeviceMessageDecoder` отбрасывает его как `too_large`.
- Предел 256 КБ из реш. 235 защищает БД, но не память процесса.

**Сценарий:**
1. Скомпрометированное устройство (любая учётка роли `device`) публикует в свой `rf/v1/dev/<id>/events` пару десятков сообщений по 100–250 МБ с QoS 1. ACL это разрешает.
2. Брокер держит их в памяти и в persistence. Бэкенд получает до 1 000 таких сообщений одновременно (Receive Maximum) и складывает их копии в очередь.
3. На стенде брокер, бэкенд и БД делят одну ВМ Docker Desktop на 7,65 ГиБ (K4.md). Память кончается раньше, чем разобрана пачка: бэкенд завершается по `ExitOnOutOfMemoryError`, либо OOM-killer убивает брокер.
4. Сообщения не подтверждены, поэтому после рестарта брокер доставляет их снова, и всё повторяется.

Реш. 241 защищает именно от «злонамеренного агента», но этот путь его предел обходит.

**Исправление:**
- В `mosquitto.conf` задать `max_packet_size 262144` (с запасом над 256 КБ), желательно ещё `max_queued_bytes`.
- У клиента бэкенда задать `.restrictions().maximumPacketSize(...)`: по MQTT 5 брокер не перешлёт ему пакет больше этого размера.
- Добавить интеграционный тест: сообщение сверх предела до бэкенда не доходит.

**Решения:** 235, 241.

### R-2. При недоступной БД или во время рестарта бэкенда брокер молча теряет QoS 1 сообщения устройств сверх ≈ 2 000

**Где:**
- `deploy/mosquitto/mosquitto.conf`: `max_queued_messages` не задан, по умолчанию 1 000 на клиента.
- `server/data/src/main/kotlin/ru/restofleet/server/data/config/DataConfig.kt:44`: `receiveMaximum = 1_000`.
- `IngestPipeline.kt:91-116`: при временном сбое та же пачка повторяется бесконечно, без ack.

**Что не так:**
- Пока БД недоступна, конвейер держит пачку неподтверждённой. Брокер шлёт бэкенду не больше Receive Maximum сообщений в полёте и копит в очереди его сессии ещё не больше `max_queued_messages` (по умолчанию 1 000, документация Mosquitto).
- Всё сверх этого Mosquitto выбрасывает. В лог пишется только «Outgoing messages are being dropped».
- Устройство при этом уже получило PUBACK от брокера, и агент удалил запись из outbox. В итоге `events`, `cmd/res`, `shadow/reported` и `status` пропадают безвозвратно.
- Решения 230 и 235 утверждают обратное: «брокер копит сообщения, пока бэкенд недоступен», «при недоступной БД сообщения копятся у брокера».
- Телеметрия (QoS 0) в это время не копится вовсе (`queue_qos0_messages false`). Так задумано протоколом, но в решениях это тоже не сказано.

**Сценарий:**
1. На стенде 5 000 MQTT-устройств. 30–60 с уходят на перезапуск PostgreSQL или на `docker compose up -d backend` (реш. 289).
2. События (`fg_changed`, `probe_state`, `threshold`, `package_changed`), итоги команд и `online` переподключившихся устройств набирают 2 000 сообщений за десятки секунд.
3. Всё, что сверх, теряется. Устройство, чьё `online` пропало, остаётся в БД `offline` до следующего переподключения: агент шлёт `online` один раз на соединение.

**Исправление:**
- Явно задать `max_queued_messages` (0 или достаточно большое значение) и вместе с ним `max_queued_bytes` как предохранитель по памяти.
- Записать в решениях фактический предел буфера бэкенда и потерю телеметрии на время его недоступности.
- Добавить К3-сценарий «БД недоступна N с → после восстановления записаны все QoS 1 сообщения».

**Решения:** 230, 235, P10.

### R-3. Повторная регистрация агента оставляет тень и outbox прежнего устройства: `desired` нового устройства игнорируется, старые данные уходят под новым `deviceId`

**Где:**
- `agent/domain/src/commonMain/kotlin/ru/restofleet/agent/domain/usecase/Provision.kt:39-57`: сохраняется только учётка.
- `agent/data/src/main/kotlin/ru/restofleet/agent/data/di/MqttModule.kt:76`: один файл `shadow.json` на устройство, без привязки к `deviceId`.
- `agent/domain/src/commonMain/kotlin/ru/restofleet/agent/domain/usecase/ApplyDesiredShadow.kt:29-30`: `IGNORE_OLDER`.
- `agent/data/src/main/kotlin/ru/restofleet/agent/data/mqtt/MqttBrokerSession.kt:102-106`: топик записи outbox вычисляется в момент публикации из текущей учётки.
- `server/domain/src/main/kotlin/ru/restofleet/server/domain/provisioning/usecase/ProvisionDevice.kt:72`: новое устройство получает `desired` v1.

**Что не так:** при смене учётки агент не сбрасывает состояние прежнего устройства: применённую тень с её `version`, outbox и `ProcessedCommands`.

**Сценарий:**
1. Эмулятор работал в MVP с учёткой `dev-emu-*` (`DEV_CONFIG`), и оператор публиковал ему `desired`, например v12, как в фикстурах.
2. На шаге S1 `DEV_PROVISION` выдаёт новый `d-<uuid>`. Повторный прогон S1 на том же эмуляторе даёт то же самое.
3. Retained `desired` v1 нового устройства отбрасывается как старая (`1 < 12`). `reported` не уходит, `delta` не пустеет, S4 не выполняется.
4. Агент продолжает работать по целям проб, `watchedApps` и порогам прежнего устройства.
5. Недосланные записи outbox старой учётки (телеметрия, события) уходят в топики нового устройства, и бэкенд записывает чужие исторические данные на новый `deviceId`.

**Исправление:**
- При сохранении учётки с другим `deviceId` до `store.save` очищать `ShadowStore`, outbox и `ProcessedCommands`. Другой вариант — хранить их с ключом `deviceId`.
- Добавить тест: после перерегистрации `desired` v1 применяется.

**Решения:** 271 и 273 этот случай не рассматривают.

### R-4. Задержка первой команды в каждом прогоне включает вход измерителя (`/auth/login` с bcrypt): опубликованный «максимум» MQTT — артефакт измерения

**Где:**
- `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/data/rest/OkHttpControlApi.kt:83-101`: `token ?: login()` — ленивый вход при первом запросе.
- `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/domain/runtime/ExperimentRun.kt:117-119`: `sentNanos` берётся до `control.sendCommand`.

**Что не так:** до окна измеритель ни разу не обращается к `ControlApi`. Поэтому первый `POST /commands` начинается с входа: TCP к `backend-ctl` и bcrypt пароля admin. Всё это время попадает в задержку первой команды.

**Подтверждение по данным:**
- `docs/experiments/data/raw/mqtt-1000/commands.csv`: первая команда — 221,061 мс, вторая — 14,635 мс.
- В `docs/experiments/results.md:43-45` столбец «макс.» MQTT — 140,3 / 221,1 / 216,9 мс, тогда как следующие по величине задержки около 18 мс (сводка ревьюера по всем пяти MQTT-прогонам).
- Среднее задержки тоже завышено. p50 и p95 не затронуты: искажена одна выборка из 240.

**Исправление:**
- Выполнять вход и один прогревочный запрос (например, `control.deviceStatus(...)`) в фазе прогрева.
- Пересчитать «макс.» и среднее или исключить первую команду, как минимум — оговорить это в `results.md`.

**Решение:** 287 (задержка «от момента перед `POST /commands`»).

### R-5. Окно замера короче TTL кэша Basic: CPU сервера в REST измерен в нестационарном режиме, вывод «в 10,1 раза» держится на всплеске bcrypt

**Где:**
- `tools/experiments/scenarios/rest*.yaml`: `warmupSec: 30`, `durationSec: 240`.
- `server/data/src/main/kotlin/ru/restofleet/server/data/security/Secrets.kt:100-103`: TTL отсчитывается от записи и при попаданиях не продлевается.
- `docs/experiments/results.md:121, 138`.

**Что не так:** все N устройств проходят первую bcrypt-проверку за время разгона, и их записи в кэше истекают почти одновременно через 5 мин. Окно (240 с после 30 с прогрева) короче TTL.

**Подтверждение по данным:**
- `docs/experiments/data/raw/rest5-5000/docker-stats.csv`: CPU backend держится около 50 % до +296 с от начала разгона.
- Дальше — 205 → 699 → 878 → 893 → … % на отметках +301…+336 с. Это ровно истечение TTL: bcrypt занимает почти все 10 ядер ВМ.
- Отсюда среднее «156,5 % одного ядра, в 10,1 раза больше MQTT» (`results.md:121`).
- При N ≤ 1 000 в окно не попадает ни одной повторной проверки. В установившемся режиме их ≈ N/300 в секунду, так что CPU REST при N ≤ 1 000 занижен, а при N = 5 000 завышен всплеском.
- Оговорка `results.md:138` описывает только всплеск при N = 5 000, а заниженный CPU при N ≤ 1 000 не упоминает.

**Последствие для бэкенда:** после массового подключения REST-парка кэш даёт синхронный «шторм» bcrypt раз в 5 мин. Ядра заняты полностью десятки секунд, а в это же время обслуживаются вход, провижининг и опросы.

**Исправление:**
- Эксперимент: окно ≥ 2 × TTL или прогрев ≥ TTL. Либо считать CPU по установившемуся интервалу и явно учесть N/300 bcrypt/с. Пересчитать вывод о CPU REST.
- Бэкенд: случайный разброс TTL (например, 5 мин ± 20 %) или скользящее продление при попадании.

**Решения:** 213 («эксперимент измеряет протокол, а не bcrypt» — при N = 5 000 это не выполнено), 287.

---

## Minor

### Бэкенд и стенд

#### R-6. Непарные суррогаты `\uD800…\uDFFF` в payload проходят декодер, но `jsonb` их отвергает (обход реш. 241)

**Где:**
- `server/data/src/main/kotlin/ru/restofleet/server/data/ingest/DeviceMessageDecoder.kt:74`: проверяется только NUL.
- `DeviceMessageDecoder.kt:133, 161`: в `jsonb` пишется исходный текст сообщения.
- `server/data/src/main/kotlin/ru/restofleet/server/data/db/Tables.kt:9`, `V1__core.sql:98, 124`.

**Что не так:** kotlinx принимает `"\ud800"` без пары. PostgreSQL требует для `jsonb` корректных суррогатных пар (документация, «JSON Types») и отвергает такую строку детерминированно, SQLState класса 22.

**Сценарий:** устройство раз в 500 мс шлёт телеметрию с полем `"x":"\ud800"`. Каждая пачка с этим сообщением падает и разбирается по одному: до 500 транзакций вместо одной. Реш. 241 должно было исключить именно это.

REST не затронут: `RestPayloadDecoder` пересериализует тело, и суррогат превращается в U+FFFD.

**Исправление:** считать непарные суррогатные экранирования `undecodable` (`bad_unicode`) или писать в `payload` пересериализованный `JsonElement`. Добавить кейс в `DeviceMessageDecoderTest`.

**Решение:** 241.

#### R-7. Basic-аутентификация устройств без rate limit: при известном `deviceId` каждый неверный пароль стоит bcrypt

**Где:**
- `server/domain/src/main/kotlin/ru/restofleet/server/domain/polling/usecase/AuthenticateDevice.kt:28-47`;
- `server/api/src/main/kotlin/ru/restofleet/server/api/Api.kt:70`;
- `server/api/src/main/kotlin/ru/restofleet/server/api/auth/Security.kt:105-112`.

**Что не так:** `/api/v1/poll/*` открыт через nginx на 8443 без лимита. Неверный пароль для существующего устройства — всегда запрос в БД и bcrypt (cost 10), и тёплый кэш при другом дайджесте от этого не спасает.

**Сценарий:**
- Злоумышленник знает `deviceId`: он виден в топиках, в MQTT Explorer, в логах.
- Поток запросов с неверным паролем загружает `Dispatchers.Default` и пул БД. Вместе с R-5 это добавляется к штатному «шторму».
- Для неизвестного `deviceId` bcrypt не выполняется. Время ответа выдаёт, существует ли такой `deviceId`.

**Исправление:** лимит неудач по `(IP, deviceId)` — отдельный `RateLimitName` на `/poll`, который считает только ответы 401. Либо отрицательный кэш на несколько секунд.

**Связь с документацией:** в арх. разд. 12 rate limit указан только для `/auth/login` и `/provision`.

#### R-8. Гонка кэша Basic с выводом из эксплуатации: выведенное устройство ещё до 5 мин проходит аутентификацию

**Где:**
- `AuthenticateDevice.kt:40-46`: чтение хэша, затем bcrypt, затем `cache.put`.
- `server/domain/src/main/kotlin/ru/restofleet/server/domain/device/usecase/DecommissionDevice.kt:55-58`: `invalidate` после commit.
- `Secrets.kt:100-103`.

**Сценарий:**
1. Опрос прочитал хэш до commit вывода из эксплуатации.
2. Пока идёт bcrypt (≈ 50–100 мс), `DecommissionDevice` фиксирует транзакцию и сбрасывает кэш.
3. `verify` кладёт запись в кэш на 5 мин. При попадании статус устройства не проверяется.
4. Всё это время `/poll/commands` отдаёт устройству команды, которые остались `queued`: `DecommissionDevice` их не отменяет. `HandleCommandResult` принимает итоги.

**Исправление:**
- После `put` перепроверять статус либо вести счётчик поколений при `invalidate`.
- При выводе из эксплуатации переводить незавершённые команды в `failed/decommissioned`.

**Решение:** 213.

#### R-9. REST-доставка команды — «не больше одного раза»: `delivered` ставится до того, как ответ дошёл до устройства

**Где:**
- `server/domain/src/main/kotlin/ru/restofleet/server/domain/polling/usecase/PollCommands.kt:23-27`;
- `server/data/src/main/kotlin/ru/restofleet/server/data/db/ExposedCommandRepository.kt:62-75`;
- `server/api/src/main/kotlin/ru/restofleet/server/api/poll/PollRoutes.kt:31-34`.

**Сценарий:** обрыв или таймаут клиента после commit `UPDATE … RETURNING`, но до получения тела ответа. Команда навсегда остаётся `delivered`, повторно не выдаётся и уходит в `expired`. В MQTT доставка гарантирована хотя бы один раз: QoS 1 и сессия.

В прогонах К4 такого не случилось (240/240). Но сравнение надёжности протоколов в записке опирается на эту асимметрию, а она нигде не оговорена.

**Исправление:** выдавать повторно `delivered` без итога после таймаута либо подтверждать получение. Как минимум оговорить в `results.md` и в реш. 281.

**Решение:** 281.

#### R-10. Пароли DynSec передаются в argv `mosquitto_ctrl` внутри контейнера

**Где:**
- `deploy/mosquitto/dynsec-dev.sh:28`: `-P '$DYNSEC_ADMIN_PASSWORD'`.
- `dynsec-dev.sh:86, 89, 95`: `createClient … -p '<пароль>'`.
- `deploy/mosquitto/test-dynsec-dev.sh:40, 42, 57`.

**Что не так:** на хост пароли не попадают, но каждый из десятков вызовов `ctrl` кладёт пароль администратора DynSec в командную строку процесса контейнера. На Linux-хосте эти процессы видны любому пользователю в `ps` хоста. Комментарий скрипта признаёт это («в контейнере — на миг»).

**Исправление:** как в `test-backend.sh` (`broker_login`): файл опций `$XDG_CONFIG_HOME/mosquitto_ctrl` с правами 600 внутри контейнера.

#### R-11. Интеграционный тест ACL не проверяет главное правило P5: запись устройства в свои `shadow/desired` и `cmd/req`

**Где:**
- `server/data/src/test/kotlin/ru/restofleet/server/data/mqtt/BrokerAclAndPublisherTest.kt:54-56`: проверяется публикация в `cmdReq(other)`.
- `deploy/mosquitto/test-dynsec-dev.sh:53`: на стенде проверен только свой `cmd/req`.

**Что не так:** запись в чужой `cmd/req` запретил бы и старый ACL `%u/#`, поэтому тест P5 не ловит. Запись устройства в свой retained `shadow/desired` не проверяется нигде.

**Сценарий:** регрессия в `dynsec-roles.sh` (возврат к `rf/v1/dev/%u/#`) оставит все тесты зелёными.

**Исправление:** публиковать в `Topics.cmdReq(me)` и `Topics.shadowDesired(me)` и ожидать `NOT_AUTHORIZED`.

**Решение:** P5.

### Агент

#### R-12. Отмена мастера после ответа 201 теряет учётку

**Где:**
- `agent/presentation/src/main/kotlin/ru/restofleet/agent/presentation/setup/viewmodel/SetupViewModel.kt:69-88`: регистрация идёт в scope ViewModel.
- `agent/domain/src/commonMain/kotlin/ru/restofleet/agent/domain/usecase/Provision.kt:48-51, 66-70`: `ensureActive` пробрасывает отмену.

**Сценарий:** экран закрыли во время регистрации, отмена пришла после ответа бэкенда. Код погашен, на сервере осталось устройство-сирота, `store.save` не выполнен.

**Исправление:** выполнять `persist` под `withContext(NonCancellable)` или в процессном scope.

**Решение:** 269.

#### R-13. Экран статуса без перехода в мастер

**Где:**
- `agent/presentation/src/main/kotlin/ru/restofleet/agent/presentation/root/viewmodel/RootReducer.kt:14-18`: `RegistrationResolved` обрабатывается только из `LOADING`.
- `IStatusRouter`: перехода в мастер нет.

**Сценарий:** старт при временном сбое Keystore. `Unavailable` ведёт на экран статуса (реш. 274), а записи на самом деле нет. Экран предлагает «пройдите настройку», но в мастер не попасть без перезапуска Activity.

**Исправление:** при `NotRegistered` в снимке переводить Root в `SETUP` либо добавить кнопку.

**Решение:** 274.

#### R-14. Состояние меняется до постановки события в outbox: сбой outbox теряет переход

**Где:**
- `agent/domain/src/commonMain/kotlin/ru/restofleet/agent/domain/usecase/RunProbes.kt:36-50`;
- `EvaluateThresholds.kt:31-34`;
- `PublishPackageChange.kt:33-37`.

**Сценарий:** `outbox.enqueue` бросает (Room, переполненный диск).
- Автомат уже в `DOWN`, зона уже «внутри», ключ дедупликации уже записан. Поэтому `probe_state` и `threshold` не придут до следующего перехода, а настоящий повторный `package_changed` в окне 5 с отбросится.
- В `RunProbes` после сбоя на первой цели остальные не учитываются, и `probe` не уходит.

**Исправление:** откатывать состояние при исключении публикации.

**Решения:** 263, 264, 267.

#### R-15. `ProbeLoop` при перезапуске не соблюдает минимальный интервал проб

**Где:** `agent/data/src/main/kotlin/ru/restofleet/agent/data/probes/ProbeLoop.kt:47-67`.

**Что не так:** при любом изменении списка целей (`collectLatest`) все цели проверяются сразу. `ProbeSchedule.nextRunAt` и `lastRunAt` не используются.

**Сценарий:** частые правки `desired.probes` заставляют release-агент дёргать цели чаще раза в 30 с.

**Исправление:** хранить `lastRunAt` по `id` и ждать `nextRunAt`.

**Решения:** 228, 263.

#### R-16. При `ECHO_EQUAL` `reported` строится из пришедшего `desired`, а не из применённого

**Где:** `agent/domain/src/commonMain/kotlin/ru/restofleet/agent/domain/usecase/ApplyDesiredShadow.kt:31-42`.

**Сценарий:**
1. Агент MVP применил vN без `expectedStatus`.
2. Агента обновили, и retained vN приходит уже с этим полем.
3. `reported` его отражает, и `delta` пустеет. Но пробы идут по файлу без поля: успехом считается любой код < 400.

**Исправление:** при `ECHO_EQUAL` и расхождении пересохранять тень.

**Решение:** 219.

#### R-17. Запрет редиректов и повторов для одноразового кода не закреплён тестом

**Где:**
- `agent/data/src/main/kotlin/ru/restofleet/agent/data/provisioning/OkHttpProvisioningApi.kt:44-55`;
- `agent/data/src/main/kotlin/ru/restofleet/agent/data/di/ProvisioningModule.kt:35-40`.

**Что не так:** запрет задан только при настройке клиента в DI. Класс принимает любой `baseClient`, и тестовый клиент редиректы не выключает. Если настройку в DI потерять, ответ 307/308 повторит POST с кодом на адрес из `Location`.

**Исправление:**
- В `provision` строить клиент через `client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)`.
- Добавить тесты: «307 → `rejected`, ровно один запрос» и «чужой SAN при сертификате от CA стенда → `tls_error`».

**Решения:** 269, 270.

### Симулятор и эксперимент

#### R-18. Перцентили считаются только по доставленным командам; цели команд включают устройства, которые не подключились

**Где:**
- `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/data/csv/CsvResultSink.kt:65`;
- `tools/experiments/analyze.py:104, 128`;
- `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/domain/usecase/RunExperiment.kt:68`.

**Что не так:** недоставленные команды выпадают из выборки, так что при потерях p95 вышел бы оптимистичным. Устройства с неудачным `open()` остаются в `targets`.

Сейчас на данные это не влияет: 240/240 и `connect_failures = 0` во всех прогонах.

**Исправление:** считать недоставленные как +∞ (p95 — «> добора», если их больше 5 %) и исключать неподключённые устройства из `targets`.

#### R-19. Возобновление регистрации сдвигает раскладку ролей; сетевой сбой после погашения кода оставляет устройство без учётки

**Где:**
- `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/domain/usecase/RegisterDevices.kt:39, 52-61`;
- `OkHttpControlApi.kt:144-145`.

**Что не так:**
- Строка i кэша не равна слоту i. Неудача в середине пачки сдвигает соответствие: «кухня, зал, зал» вместо «кухня, кухня, зал».
- Повтор `/provision` после сетевой ошибки получает `token_used`. Устройство остаётся на бэкенде, но без учётки в кэше.

**Исправление:** хранить индекс слота в кэше. Сетевой отказ `/provision` помечать как «неизвестно».

**Решения:** 286, 288.

#### R-20. `run-experiment.sh`: сбой `register` оставляет повышенный rate limit; имя сценария не проверяется перед `rm -rf`

**Где:** `tools/experiments/run-experiment.sh:63-79`.

**Что не так:**
- При `failed > 0` скрипт выходит по `set -e` до `backend_with 90000 60`, и бэкенд остаётся с `RF_RATELIMIT_PROVISIONPERMINUTE=100000`.
- `run ../../../deploy/seed/restaurants` проходит проверку `-f`, потому что такой `.yaml` существует. Затем выполняется `rm -rf logs/experiments/../../../deploy/seed/restaurants` — путь вне репозитория.

**Исправление:**
- `trap 'backend_with 90000 60' EXIT` в ветке `register`.
- Проверять имя по `^[A-Za-z0-9._-]+$` без `..` и сверять `name:` в YAML с именем файла.

**Решение:** 289.

#### R-21. Права на каталоги и кэш учёток рассчитаны только на macOS; запись учёток пачками

**Где:**
- `run-experiment.sh:22-26, 77-79`;
- `server/simulator/docker-entrypoint.sh:20`;
- `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/data/store/JsonlCredentialStore.kt:52-61`.

**Что не так:**
- Каталоги 700 принадлежат пользователю хоста, а процесс в контейнере работает от uid 10001. На Linux `register` падает на первой записи, а `experiment` — в `CsvResultSink` только в конце прогона, через ≈ 6 мин.
- Учётки дописываются пачками до 64 после `awaitAll`. Если процесс убит, до 64 зарегистрированных устройств останутся без учётки.
- Права уже существующего файла не проверяются.

**Исправление:** `chown 10001` или `--user`, запись каждой учётки сразу, предупреждение при правах шире 600.

**Решения:** 286, 291 (оно же предлагает Linux-хост).

#### R-22. Свободная память в профилях при реальном времени не убывает

**Где:** `server/simulator/src/main/kotlin/ru/restofleet/server/simulator/domain/profile/Profiles.kt:160`.

**Что не так:** `(FREE_MB_PER_HOUR * dtSec / 3600).toLong()` при тике 30 с и `timeScale = 1` равно 0. KDoc и FR-SM-03 обещают медленное убывание.

**Исправление:** хранить `freeMb` в `Double` или накапливать дробную часть.

#### R-23. Сэмплер `docker stats` и `analyze.py` молча теряют данные; `analyze.py` без тестов

**Где:**
- `tools/experiments/sample_docker_stats.py:37-40, 84`: `subprocess.TimeoutExpired` не перехватывается, фоновый сэмплер умирает незаметно.
- `tools/experiments/analyze.py:73-75, 368`: число сэмплов в окне не сверяется с ожидаемым (≈ 48). При пустом CSV — падение на `min()`.

**Тесты:**
- `analyze.py` — источник всех опубликованных чисел, но тестами не покрыт.
- В `server/simulator/src/test/.../RunExperimentTest.kt:100-105` фейк сам выставляет `receivedAtNanos`. Тест не проверяет, что момент получения определяет опрос.

**Исправление:**
- Перехватывать таймаут, предупреждать, если сэмплов меньше 80 % ожидаемого.
- Добавить `unittest` на синтетических CSV.

#### R-24. Гейт Kover `domain ≥ 90 %` на домен симулятора не действует

**Где:** `server/simulator/build.gradle.kts:52-57`.

**Что не так:** домен симулятора — пакет внутри модуля, поэтому действует только модульный порог 80 %. Фактическое покрытие 98,7 %, но гейта нет, хотя `CLAUDE.md` требует его.

**Исправление:** отдельное правило Kover с фильтром по пакету `ru.restofleet.server.simulator.domain`.

**Решение:** 279.

---

## Что сделано хорошо

- **Ingest по P10 сделан аккуратно:**
  - ack строго после commit;
  - пачка собирается через `select`/`onTimeout`, сообщение на границе окна не теряется;
  - временные сбои отделены от «ядовитых» сообщений;
  - отмена конвейера отделена от бродячего `CancellationException` (`ensureActive`);
  - автопереподключение HiveMQ повторяет исходный CONNECT (реш. 259).
- **Провижининг атомарен и безопасен:**
  - код гасится одним `UPDATE … RETURNING`, есть компенсация в DynSec;
  - коды 128 бит и пароли/refresh 256 бит — из `SecureRandom`, в БД только SHA-256;
  - `no-store` на ответах с секретами, `toString` маскирует секреты во всех DTO и конфигурациях;
  - секреты передаются только файлами `/run/secrets`, а `test-backend.sh` считает их утечки в логах.
- **BOLA закрыта:**
  - устройство определяется только по топику или Basic, `HandleCommandResult` сверяет владельца команды;
  - ACL DynSec разделены по направлениям, по умолчанию deny;
  - роли берутся из одного файла и для стенда, и для интеграционных тестов на живом Mosquitto.
- **Аутентификация:**
  - JWT HS256, секрет ≥ 32 байт, проверка `iss`/`aud` и роли `admin`;
  - ротация refresh с отзывом всех сессий при повторном предъявлении (реш. 239);
  - фиктивный bcrypt для неизвестного логина;
  - rate limit по заменённому nginx `X-Forwarded-For`.
- **Архитектура и измерения:**
  - domain без фреймворков, правила Konsist проверены мутацией;
  - Koin — только внедрение через конструктор, `get()` — только в composition root;
  - фейковый `TransactionRunner` честно откатывает;
  - агент: в debug пиннинг CA стенда (fail closed), в release системное доверие, `verifyReleaseNoDebugSurface`;
  - трафик эксперимента меряется счётчиками интерфейса с отдельной сетью измерителя, сверка с `docker stats` — 2–4 %.

---

## Сводка

| Уровень | Число | ID |
|---|---|---|
| Critical | 0 | — |
| Important | 5 | R-1, R-2, R-3, R-4, R-5 |
| Minor | 19 | R-6 … R-24 |
