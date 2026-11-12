# Приложение А — Схема топиков и форматы сообщений протокола

Сообщения протокола передаются по MQTT 5.0 в топиках с префиксом `rf/v1/`, где `v1` — версия протокола. Каждое устройство имеет собственное поддерево `rf/v1/dev/{deviceId}/`; идентификатор вида `d-<UUID>` служит также именем учётной записи и идентификатором клиента в брокере. Источник истины для имён топиков — объект `Topics` модуля `shared:protocol`, в котором описаны и классы сообщений; каждый формат закреплён эталонным JSON-файлом.

## А.1 Дерево топиков

Листья поддерева устройства приведены в таблице А.1. Под устройством понимается Android-агент или виртуальное устройство симулятора, под сервером — серверная часть.

Таблица А.1 — Топики протокола rf/v1

| Топик (после `rf/v1/dev/{deviceId}/`) | Направление | QoS | Retain | Публикует | Подписан |
|---|---|---|---|---|---|
| `status` | К серверу | 1 | Да | Устройство; брокер (завещание); сервер (очистка) | Сервер |
| `telemetry` | К серверу | 0; из буфера — 1 | Нет | Устройство | Сервер |
| `probe` | К серверу | 0; из буфера — 1 | Нет | Устройство | Сервер |
| `events` | К серверу | 1 | Нет | Устройство | Сервер |
| `shadow/reported` | К серверу | 1 | Нет | Устройство | Сервер |
| `cmd/res` | К серверу | 1 | Нет | Устройство | Сервер |
| `shadow/desired` | К устройству | 1 | Да | Сервер | Устройство |
| `cmd/req` | К устройству | 1 | Нет | Сервер | Устройство |

Устройство публикует в `status` сообщение `online` после подключения и `offline` при штатном отключении, брокер — завещание (LWT) при пропаже, сервер — пустое сохраняемое сообщение при выводе устройства из эксплуатации. Сохраняемый `shadow/desired` сервер публикует при регистрации (версия 1), при изменении тени и повторно при подключении устройства, не подтвердившего версию. Сервер подписывается с QoS 1 разделяемой подпиской `$share/backend/rf/v1/dev/+/<канал>` на шесть каналов от устройств, устройство — с QoS 1 на собственные `shadow/desired` и `cmd/req`. Агент публикует телеметрию и результаты проверок с QoS 0, только если сессия активна и буфер исходящих сообщений пуст; иначе сообщение записывается в буфер и досылается с QoS 1. Симулятор буфера не имеет и публикует их всегда с QoS 0. `status` публикуется напрямую, минуя буфер; завещание `offline` с QoS 1 и флагом retain задаётся в пакете CONNECT каждого подключения. Пустые сохраняемые сообщения в `status` и `shadow/desired` при выводе устройства из эксплуатации удаляют сохранённые брокером значения; пустой `desired` агент подтверждает и не применяет.

Команда в `cmd/req` публикуется со свойствами MQTT 5.0: `Response Topic` — `rf/v1/dev/{deviceId}/cmd/res`, `Correlation Data` — байты `cmdId`, `Message Expiry Interval` — остаток срока жизни до `expiresAt` в секундах (с округлением вверх, не менее 1 с). Срок жизни — по умолчанию 300 с, допустимо 1…86 400 с. Агент отвечает всегда в собственный `cmd/res`, перенося `Correlation Data` без изменений.

Учётными записями устройств сервер управляет командами `createClient`, `disableClient` и `deleteClient`, публикуя их с QoS 1 в `$CONTROL/dynamic-security/v1`; ответы, сопоставляемые с запросом по `correlationData`, приходят в `$CONTROL/dynamic-security/v1/response`. Ветка `rf/v1/rest/{restaurantId}/broadcast` для групповых команд предусмотрена техническим заданием для этапа выпускной работы и в коде этапа курсовой работы отсутствует.

## А.2 Права ролей в брокере

Роли Dynamic Security создаются командами `mosquitto_ctrl` из скрипта `deploy/mosquitto/dynsec-roles.sh`, общего для стенда и интеграционных тестов; при каждом запуске правила ролей снимаются и добавляются заново. Доступ по умолчанию (default ACL access), действующий там, где не подходит ни одно правило роли: `publishClientSend` — `deny`, `publishClientReceive` — `deny`, `subscribe` — `deny`, `unsubscribe` — `allow` (запрет отписки нарушал бы штатное отключение). Все правила ролей разрешающие, приоритет в `addRoleACL` не задаётся; правила `subscribeLiteral`, `unsubscribeLiteral` и `unsubscribePattern` не используются. Права ролей приведены в таблицах А.2 и А.3; `%u` заменяется именем пользователя клиента, равным `deviceId`.

Таблица А.2 — Права роли `device`

| Тип правила | Шаблон топика | Доступ |
|---|---|---|
| `publishClientSend` | `rf/v1/dev/%u/<канал>`, где `<канал>` — `status`, `telemetry`, `probe`, `events`, `shadow/reported`, `cmd/res` | `allow` |
| `subscribePattern` | `rf/v1/dev/%u/shadow/desired`, `rf/v1/dev/%u/cmd/req` | `allow` |
| `publishClientReceive` | `rf/v1/dev/%u/shadow/desired`, `rf/v1/dev/%u/cmd/req` | `allow` |
| Остальное | В том числе публикация в собственные `shadow/desired` и `cmd/req` и любые топики других устройств | `deny` по умолчанию |

Учётная запись устройства создаётся одной командой `createClient` с именем пользователя и `clientid`, равными `deviceId`, и ролью `device`; вывод из эксплуатации выполняет `disableClient`.

Таблица А.3 — Права роли `backend-service`

| Тип правила | Шаблон топика | Доступ |
|---|---|---|
| `subscribePattern` | `$share/backend/rf/v1/dev/+/<канал>` для шести каналов от устройств | `allow` |
| `publishClientReceive` | `rf/v1/dev/+/<канал>` для тех же каналов | `allow` |
| `publishClientSend` | `rf/v1/dev/+/shadow/desired`, `rf/v1/dev/+/cmd/req`, `rf/v1/dev/+/status` | `allow` |
| `publishClientSend`, `publishClientReceive`, `subscribePattern` | `$CONTROL/dynamic-security/#` | `allow` |

Правило подписки содержит префикс `$share/backend/`, так как Dynamic Security сверяет весь фильтр разделяемой подписки, а правило получения — топик сообщения. Публикация в `status` нужна только для очистки при выводе устройства из эксплуатации. Права на `$CONTROL/dynamic-security/#` фактически делают сервер администратором брокера. На стенде роль назначена учётной записи `backend`; отладочная роль стенда `operator-dev` (права на `rf/v1/#`) устройствам и серверу не назначается.

## А.3 Форматы сообщений

Сообщения передаются в JSON (UTF-8): неизвестные поля игнорируются, поля со значением `null` не передаются, значения по умолчанию пишутся явно, подтип события задаётся полем `type`; время — в миллисекундах от начала эпохи Unix.

Статус `online` и завещание того же подключения (общий `connId`):

```json
{ "state": "online", "ts": 1793865600000, "connId": "k-7f3a9c",
  "agentVersion": "0.1.0-mvp", "mode": "normal", "role": "kitchen",
  "bootId": "b7f2c1d0",
  "device": { "model": "sdk_gphone64_arm64", "manufacturer": "Google",
              "osVersion": "16", "apiLevel": 36 } }
{ "state": "offline", "ts": 1793865600000, "connId": "k-7f3a9c",
  "reason": "lwt" }
```

Значения `mode` — `normal` или `device_owner`, `role` — `kitchen`, `hall` или `assembly`; `reason` в `offline` — `lwt` (завещание), `stop` (штатная остановка) или `reconfigure` (смена учётной записи). Телеметрия (недоступные считывания не передаются):

```json
{ "ts": 1793865630000, "seq": 18234,
  "battery": { "level": 87, "charging": true, "plugged": "ac",
               "tempC": 38.4 },
  "thermal": { "status": "LIGHT", "headroom": 0.62 },
  "net": { "type": "wifi", "rssi": -67, "validated": true },
  "storage": { "freeMb": 5120 }, "memory": { "availMb": 1430 },
  "uptimeSec": 360021, "foregroundApp": "com.android.chrome",
  "apps": [ { "pkg": "com.android.chrome", "versionName": "130.0",
              "versionCode": 0 } ] }
```

Отчёт `probe` содержит массив `results` с полями `id`, `ok`, `status`, `error`, `totalMs`, `dnsMs`, `connectMs`, `tlsMs`, `ttfbMs`; `error` — `dns_fail`, `timeout`, `conn_refused`, `tls_error`, `http_4xx`, `http_5xx`, `no_network`, `local_network_denied` или `other`. Типы событий перечислены в таблице А.4, примеры:

```json
{ "type": "probe_state", "ts": 1793865700000, "target": "api",
  "state": "down", "error": "timeout", "failures": 3 }
{ "type": "threshold", "ts": 1793865750000, "metric": "battery_low",
  "state": "enter", "value": 19.0, "limit": 20.0 }
```

Таблица А.4 — Типы событий канала `events`

| `type` | Поля, кроме `type` и `ts` | Назначение |
|---|---|---|
| `probe_state` | `target`, `state` (`up`, `down`), `error`, `failures` | Смена состояния цели проверки |
| `threshold` | `metric` (`battery_low`, `temp_high`, `thermal`), `state` (`enter`, `exit`), `value`, `limit` | Вход в зону порога и выход из неё |
| `fg_changed` | `from`, `to` | Смена приложения на экране |
| `fg_absent` | `pkg`, `sinceTs`, `limitMin`, `foregroundApp` | Отслеживаемое приложение не на экране дольше `fgAbsentMin` мин |
| `net_changed` | `net` (`type`, `rssi`, `validated`, `ssid`) | Смена состояния сети |
| `package_changed` | `pkg`, `change` (`added`, `replaced`, `removed`), `versionCode` | Изменение пакета |
| `agent_lifecycle` | `phase` (`boot`, `start`, `stop`) | Фаза жизненного цикла агента |
| `capability_check` | `checkId`, `status`, `raw`, `note` | Результат проверки возможностей с отладочного экрана |
| `debug_seq` | `seq`, `batchId` | Только отладочная сборка, сервер не сохраняет |

Желаемое состояние тени (показаны две цели из трёх):

```json
{ "version": 12,
  "telemetry": { "intervalSec": 30 },
  "thresholds": { "batteryLowPct": 20, "tempHighC": 45,
                  "fgAbsentMin": 10 },
  "watchedApps": [ "com.android.chrome" ],
  "probes": [
    { "id": "api", "type": "http",
      "url": "https://api.company.example/health",
      "intervalSec": 30, "timeoutMs": 5000, "failThreshold": 3,
      "reference": false },
    { "id": "ref", "type": "http", "url": "https://reference.example/",
      "intervalSec": 60, "timeoutMs": 5000, "failThreshold": 3,
      "reference": true } ] }
```

Цель может содержать ожидаемый HTTP-код `expectedStatus`: без него проверка успешна при коде меньше 400, с ним — при точном совпадении. `shadow/reported` повторяет структуру `desired` с применёнными значениями и добавляет `appliedVersion`, `appliedAt` и `errors` — коды `telemetryIntervalSec:out_of_range`, `probes:empty_id`, `probes:empty_url:<id>`, `probes:duplicate_id:<id>`, `watchedApps:empty`, `watchedApps:invalid_name:<имя>`.

Команда, ответы на неё (промежуточный `received` и итог) и итог другой команды с отказом:

```json
{ "cmdId": "c-01J9Z3", "type": "show_message",
  "params": { "text": "Перезагрузите роутер", "level": "warning" },
  "expiresAt": 1793866100000 }
{ "cmdId": "c-01J9Z3", "status": "received", "ts": 1793865800000 }
{ "cmdId": "c-01J9Z3", "status": "succeeded", "ts": 1793865801000,
  "result": {} }
{ "cmdId": "c-01J9Z6", "status": "failed", "ts": 1793866500000,
  "reason": "expired" }
```

Типы команд: `ping`, `show_message`, `collect_diagnostics`, `launch_app`, `run_probe`, `beep` и требующие режима Device Owner `reboot`, `install_update`, `lock_task`. Итог — `succeeded` с объектом `result` со строковыми значениями, `failed` или `unsupported` с полем `reason`: `expired` (время устройства превышает `expiresAt` более чем на 5 мин), `timeout`, `invalid_params:<причина>`, `unknown_type:<тип>`, `requires_device_owner`, `exception:<класс>`. Сервер ведёт статусы `queued` → `delivered` (по `received`) → `succeeded`, `failed`, `unsupported` или `expired` и сам выставляет `expired` по таймеру, `failed` с причиной `broker_unavailable` при неудачной публикации и `decommissioned` при выводе устройства из эксплуатации.
