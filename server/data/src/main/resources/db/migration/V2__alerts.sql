-- RestoFleet, этап «практика», ПМ2: тревоги R-01…R-06 (docs/stages/2-practice.md 4.3; реш. 413–416, 443).
-- parent_incident_id — без внешнего ключа: таблица incidents и ключ появятся в V3 (ПМ3).

CREATE TABLE alerts (
    id                 uuid         PRIMARY KEY,
    rule_id            varchar(8)   NOT NULL CHECK (rule_id IN ('R-01', 'R-02', 'R-03', 'R-04', 'R-05', 'R-06')),
    severity           varchar(16)  NOT NULL CHECK (severity IN ('critical', 'warning')),
    state              varchar(16)  NOT NULL CHECK (state IN ('open', 'acknowledged', 'resolved')),
    device_id          varchar(64)  NOT NULL REFERENCES devices (id),
    restaurant_id      uuid         NOT NULL REFERENCES restaurants (id),
    target             varchar(255),
    opened_at          timestamptz  NOT NULL,
    ack_by             varchar(64),
    ack_at             timestamptz,
    resolved_at        timestamptz,
    resolved_by        varchar(64),
    comment            varchar(500),
    auto               boolean      NOT NULL DEFAULT false,
    parent_incident_id uuid,
    details            jsonb        NOT NULL DEFAULT '{}'
);
-- Одна незакрытая тревога на субъект (правило, устройство, цель) — дедупликация (реш. 413)
CREATE UNIQUE INDEX alerts_active_subject_uq ON alerts (rule_id, device_id, coalesce(target, ''))
    WHERE state <> 'resolved';
CREATE INDEX alerts_opened_idx ON alerts (opened_at DESC, id DESC);
CREATE INDEX alerts_device_active_idx ON alerts (device_id) WHERE state <> 'resolved';
CREATE INDEX alerts_restaurant_idx ON alerts (restaurant_id, opened_at DESC);

-- Факты-условия правил (реш. 443): строка есть, пока факт действует
CREATE TABLE alert_conditions (
    device_id varchar(64)  NOT NULL REFERENCES devices (id),
    rule_id   varchar(8)   NOT NULL,
    target    varchar(255) NOT NULL DEFAULT '',
    facet     varchar(32)  NOT NULL,
    since     timestamptz  NOT NULL,
    details   jsonb        NOT NULL DEFAULT '{}',
    PRIMARY KEY (device_id, rule_id, target, facet)
);

-- Состояние «устройство × цель» для агрегатора инцидентов (реш. 411): автомат K и свежесть результата
CREATE TABLE probe_states (
    device_id      varchar(64) NOT NULL REFERENCES devices (id),
    target         varchar(64) NOT NULL,
    state          varchar(8)  NOT NULL CHECK (state IN ('up', 'down')),
    since          timestamptz NOT NULL,
    last_result_at timestamptz,
    PRIMARY KEY (device_id, target)
);
