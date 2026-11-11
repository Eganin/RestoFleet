-- RestoFleet, схема этапа «курсовая» (docs/stages/1-coursework.md 4.4; реш. 205, 216, 240).
-- Время — timestamptz, документы — jsonb. Гипертаблицы TimescaleDB и политика хранения — диплом (FR-BE-13).
-- Идентификаторы: рестораны, пользователи, коды и refresh-токены — uuid; устройство — его deviceId (он же MQTT
-- clientId, `d-<uuid>`), команда — cmdId (`c-<uuid>`).

CREATE TABLE restaurants (
    id         uuid         PRIMARY KEY,
    code       varchar(32)  NOT NULL UNIQUE,
    name       varchar(200) NOT NULL,
    city       varchar(200) NOT NULL,
    address    varchar(200) NOT NULL,
    timezone   varchar(64)  NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE TABLE devices (
    id                varchar(64)  PRIMARY KEY,
    restaurant_id     uuid         NOT NULL REFERENCES restaurants (id),
    role              varchar(16)  NOT NULL CHECK (role IN ('kitchen', 'hall', 'assembly')),
    name              varchar(100),
    status            varchar(16)  NOT NULL CHECK (status IN ('provisioned', 'online', 'offline', 'decommissioned')),
    transport         varchar(8)   CHECK (transport IN ('mqtt', 'poll')),
    last_seen_at      timestamptz,
    decommissioned_at timestamptz,
    created_at        timestamptz  NOT NULL,
    model             varchar(100),
    manufacturer      varchar(100),
    os_version        varchar(32),
    api_level         integer,
    agent_version     varchar(64),
    mode              varchar(32)
);
CREATE INDEX devices_restaurant_idx ON devices (restaurant_id, role, status);
CREATE INDEX devices_status_idx ON devices (status);
-- MarkStalePollers (реш. 214): только опрашивающие устройства в сети
CREATE INDEX devices_pollers_idx ON devices (last_seen_at) WHERE transport = 'poll' AND status = 'online';

-- Учётка устройства для REST-polling (P9): bcrypt-хэш; отзыв при выводе из эксплуатации
CREATE TABLE device_credentials (
    device_id     varchar(64)  PRIMARY KEY REFERENCES devices (id),
    password_hash varchar(100) NOT NULL,
    created_at    timestamptz  NOT NULL,
    revoked_at    timestamptz
);

-- Одноразовые коды регистрации (FR-BE-02): только SHA-256 кода
CREATE TABLE provisioning_tokens (
    id            uuid         PRIMARY KEY,
    token_hash    char(64)     NOT NULL UNIQUE,
    restaurant_id uuid         NOT NULL REFERENCES restaurants (id),
    role          varchar(16)  NOT NULL CHECK (role IN ('kitchen', 'hall', 'assembly')),
    name          varchar(100),
    created_at    timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    used_at       timestamptz,
    device_id     varchar(64)  REFERENCES devices (id)
);
CREATE INDEX provisioning_tokens_restaurant_idx ON provisioning_tokens (restaurant_id);

-- История MQTT-сессий (FR-BE-12, P3): одна строка на connId
CREATE TABLE device_sessions (
    device_id       varchar(64) NOT NULL REFERENCES devices (id),
    conn_id         varchar(64) NOT NULL,
    connected_at    timestamptz NOT NULL,
    disconnected_at timestamptz,
    reason          varchar(32),
    PRIMARY KEY (device_id, conn_id)
);
CREATE INDEX device_sessions_latest_idx ON device_sessions (device_id, connected_at DESC);

-- Тени (FR-BE-20): документы без служебных ключей; reported_version = appliedVersion
CREATE TABLE shadows (
    device_id          varchar(64) PRIMARY KEY REFERENCES devices (id),
    desired            jsonb       NOT NULL,
    desired_version    bigint      NOT NULL,
    desired_updated_at timestamptz NOT NULL,
    reported           jsonb,
    reported_version   bigint,
    applied_at         timestamptz,
    reported_at        timestamptz,
    errors             jsonb       NOT NULL DEFAULT '[]'::jsonb
);

-- Телеметрия (FR-BE-11): уникальность (device_id, ts), дубли досылки отбрасываются (реш. 205)
CREATE TABLE telemetry (
    device_id     varchar(64)      NOT NULL REFERENCES devices (id),
    ts            timestamptz      NOT NULL,
    seq           bigint           NOT NULL,
    battery_level smallint,
    charging      boolean,
    temp_c        double precision,
    thermal       varchar(16),
    rssi          integer,
    free_mb       bigint,
    avail_mb      bigint,
    fg_app        varchar(255),
    payload       jsonb            NOT NULL,
    PRIMARY KEY (device_id, ts)
);

-- Результаты проверок: строка на цель; повтор того же прогона отбрасывается
CREATE TABLE probe_results (
    device_id  varchar(64) NOT NULL REFERENCES devices (id),
    ts         timestamptz NOT NULL,
    target     varchar(64) NOT NULL,
    ok         boolean     NOT NULL,
    status     integer,
    error      varchar(32),
    total_ms   bigint      NOT NULL,
    dns_ms     bigint,
    connect_ms bigint,
    tls_ms     bigint,
    ttfb_ms    bigint,
    PRIMARY KEY (device_id, ts, target)
);

-- События: идентификатора в протоколе нет, дубль допустим (реш. 207)
CREATE TABLE events (
    id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id varchar(64) NOT NULL REFERENCES devices (id),
    ts        timestamptz NOT NULL,
    type      varchar(32) NOT NULL,
    payload   jsonb       NOT NULL
);
CREATE INDEX events_device_ts_idx ON events (device_id, ts DESC);

-- Команды (FR-BE-40, 41)
CREATE TABLE commands (
    cmd_id       varchar(64) PRIMARY KEY,
    device_id    varchar(64) NOT NULL REFERENCES devices (id),
    type         varchar(64) NOT NULL,
    params       jsonb       NOT NULL,
    status       varchar(16) NOT NULL
        CHECK (status IN ('queued', 'delivered', 'succeeded', 'failed', 'unsupported', 'expired')),
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    delivered_at timestamptz,
    finished_at  timestamptz,
    result       jsonb,
    reason       text
);
CREATE INDEX commands_device_status_idx ON commands (device_id, status);
-- ExpireCommands: только незавершённые
CREATE INDEX commands_pending_expiry_idx ON commands (expires_at) WHERE status IN ('queued', 'delivered');

-- Пользователи и refresh-токены (FR-BE-52, реш. 216)
CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    login         varchar(64)  NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    role          varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL
);

CREATE TABLE refresh_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    token_hash char(64)    NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX refresh_tokens_active_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
