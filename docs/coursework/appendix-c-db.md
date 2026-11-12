# Приложение В — Таблицы базы данных

Схема базы данных этапа курсовой работы создаётся миграцией Flyway `V1__core.sql` модуля `server:data`. Логическая модель данных приведена на рисунке В.1, состав таблиц — в таблице В.1 (поля создания и изменения записей опущены). Время хранится в типе `timestamptz`, идентификаторы имеют вид `d-<UUID>` (устройство) и `c-<UUID>` (команда).

[[FIG: er-model | Логическая модель данных этапа курсовой работы | ER-диаграмма: restaurants 1—N devices; restaurants 1—N provisioning_tokens; provisioning_tokens N—0..1 devices (device_id); devices 1—1 device_credentials; devices 1—1 shadows; devices 1—N device_sessions, telemetry, probe_results, events, commands; users 1—N refresh_tokens. Указать первичные ключи (составные у device_sessions, telemetry, probe_results) и внешние ключи device_id, restaurant_id, user_id; отметить JSONB-поля (desired, reported, errors, payload, params, result)]]

Таблица В.1 — Таблицы базы данных этапа курсовой работы

| Таблица | Основные поля | Назначение |
|---|---|---|
| `restaurants` | `id`, `code` (уникальный), `name`, `city`, `address`, `timezone` | Рестораны |
| `devices` | `id`, `restaurant_id`, `role`, `name`, `status`, `transport`, `last_seen_at`, `decommissioned_at`; сведения об устройстве `model`, `manufacturer`, `os_version`, `api_level`, `agent_version`, `mode` | Реестр устройств: роль (кухня, зал, сборка), статус (`provisioned`, `online`, `offline`, `decommissioned`), транспорт (`mqtt` или `poll`) |
| `device_credentials` | `device_id`, `password_hash`, `revoked_at` | bcrypt-хэш пароля устройства для REST-polling |
| `provisioning_tokens` | `id`, `token_hash`, `restaurant_id`, `role`, `name`, `expires_at`, `used_at`, `device_id` | Одноразовые коды регистрации и зарегистрированное по коду устройство |
| `device_sessions` | `device_id`, `conn_id`, `connected_at`, `disconnected_at`, `reason` | История подключений, ключ — пара «устройство, `connId`» |
| `shadows` | `device_id`, `desired`, `desired_version`, `reported`, `reported_version`, `applied_at`, `reported_at`, `errors` | Тени устройств (`desired`, `reported`, `errors` — JSONB) |
| `telemetry` | `device_id`, `ts`, `seq`, `battery_level`, `charging`, `temp_c`, `thermal`, `rssi`, `free_mb`, `avail_mb`, `fg_app`, `payload` | Временной ряд телеметрии, ключ — пара «устройство, время» |
| `probe_results` | `device_id`, `ts`, `target`, `ok`, `status`, `error`, `total_ms`, `dns_ms`, `connect_ms`, `tls_ms`, `ttfb_ms` | Результаты проверок, ключ — «устройство, время, цель» |
| `events` | `id`, `device_id`, `ts`, `type`, `payload` | События устройств |
| `commands` | `cmd_id`, `device_id`, `type`, `params`, `status`, `expires_at`, `delivered_at`, `finished_at`, `result`, `reason` | Команды и их статусы |
| `users` | `id`, `login`, `password_hash`, `role` | Пользователи программного интерфейса |
| `refresh_tokens` | `id`, `user_id`, `token_hash`, `expires_at`, `revoked_at` | Маркеры обновления (хэш SHA-256) |
