-- RestoFleet, этап «практика», ПМ3: сессии пользователей (реш. 417) и сводные инциденты (реш. 409–412, 416).

-- Семейство refresh-токенов и потолок сессии. Прежние токены: каждый — своё семейство, потолок = его срок.
ALTER TABLE refresh_tokens ADD COLUMN family_id uuid;
ALTER TABLE refresh_tokens ADD COLUMN session_expires_at timestamptz;
UPDATE refresh_tokens SET family_id = id, session_expires_at = expires_at;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE refresh_tokens ALTER COLUMN session_expires_at SET NOT NULL;
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id) WHERE revoked_at IS NULL;

-- Сводные инциденты (спецификация 4.3 + resolve_reason, missed_ticks, ack_by, ack_at, comment, реш. 412).
-- restaurants и evidence — jsonb: состав пишется и читается целиком, поиск по ресторану не нужен (реш. 452).
CREATE TABLE incidents (
    id             uuid         PRIMARY KEY,
    kind           varchar(32)  NOT NULL CHECK (kind IN ('restaurant_network', 'backend_global', 'backend_local')),
    rule_id        varchar(8)   NOT NULL CHECK (rule_id IN ('R-07', 'R-08', 'R-09')),
    target         varchar(64)  NOT NULL,
    restaurants    jsonb        NOT NULL,
    started_at     timestamptz  NOT NULL,
    resolved_at    timestamptz,
    resolve_reason varchar(16)  CHECK (resolve_reason IN ('cleared', 'superseded')),
    missed_ticks   integer      NOT NULL DEFAULT 0,
    ack_by         varchar(64),
    ack_at         timestamptz,
    comment        varchar(500),
    evidence       jsonb        NOT NULL
);
CREATE INDEX incidents_open_idx ON incidents (started_at) WHERE resolved_at IS NULL;
CREATE INDEX incidents_started_idx ON incidents (started_at DESC, id DESC);

-- Подавление дочерних тревог (реш. 416): ключ на инцидент
ALTER TABLE alerts ADD CONSTRAINT alerts_parent_incident_fk FOREIGN KEY (parent_incident_id) REFERENCES incidents (id);
CREATE INDEX alerts_parent_idx ON alerts (parent_incident_id) WHERE parent_incident_id IS NOT NULL;

-- Класс ошибки последнего probe_state (ошибки проверок в доказательствах)
ALTER TABLE probe_states ADD COLUMN error varchar(32);

-- История агрегатора (реш. 411): строка на минуту, ресторан и цель; сырьё экрана «Доступность» (диплом)
CREATE TABLE probe_agg_1m (
    restaurant_id  uuid        NOT NULL REFERENCES restaurants (id),
    target         varchar(64) NOT NULL,
    bucket         timestamptz NOT NULL,
    devices_total  integer     NOT NULL,
    devices_online integer     NOT NULL,
    target_online  integer     NOT NULL,
    target_down    integer     NOT NULL,
    ref_down       integer     NOT NULL,
    PRIMARY KEY (restaurant_id, target, bucket)
);
