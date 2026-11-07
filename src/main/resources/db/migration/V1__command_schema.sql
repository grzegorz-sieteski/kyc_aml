-- The write side of the risk module (design section 9). Only the persistence adapter and the outbox
-- relay touch this schema; the views live in schema query (V2) and only the projector writes there.
CREATE SCHEMA command;

-- One row per customer per booking centre: the current state. The id is generated in the domain and
-- never leaves the service (D-05). tenant_id is the booking centre.
CREATE TABLE command.risk_profile
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID                    NOT NULL,
    -- COLLATE "C": BR-06 compares customer numbers exactly as supplied, letter case included.
    customer_number VARCHAR(64) COLLATE "C" NOT NULL,
    evaluation      JSONB                   NOT NULL,           -- policy, total, evaluated track, explanation
    evaluated_at    TIMESTAMPTZ             NOT NULL,           -- the moment of the latest evaluation
    decided_track   VARCHAR(10),                                -- null while no decision stands (BR-31)
    latest_decision JSONB,
    updated_at      TIMESTAMPTZ             NOT NULL,
    version         BIGINT                  NOT NULL,           -- optimistic lock (BR-30)

    CONSTRAINT uq_risk_profile_tenant_customer UNIQUE (tenant_id, customer_number),            -- BR-05
    CONSTRAINT ck_risk_profile_number_length CHECK (length(customer_number) BETWEEN 1 AND 64), -- BR-06
    CONSTRAINT ck_risk_profile_number_no_control CHECK (customer_number !~ '[\x01-\x1F\x7F-\x9F]'), -- BR-06
    CONSTRAINT ck_risk_profile_decided_track CHECK (decided_track IN ('YELLOW', 'ORANGE', 'RED')),
    CONSTRAINT ck_risk_profile_version CHECK (version >= 1)
);

-- Every recorded fact, appended with each new version and never changed or removed (BR-29, BR-44).
CREATE TABLE command.risk_profile_history
(
    profile_id  UUID         NOT NULL REFERENCES command.risk_profile (id),
    version     BIGINT       NOT NULL,
    fact_kind   VARCHAR(20)  NOT NULL CHECK (fact_kind IN ('EVALUATION', 'DECISION')),
    fact        JSONB        NOT NULL,
    actor_id    VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (profile_id, version)
);

-- A trigger, not a REVOKE: a REVOKE does not bind the table owner, a trigger binds everyone.
CREATE FUNCTION command.refuse_history_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'risk_profile_history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_risk_profile_history_append_only
    BEFORE UPDATE OR DELETE
    ON command.risk_profile_history
    FOR EACH ROW
EXECUTE FUNCTION command.refuse_history_change();

-- The transactional outbox: written in the same transaction as the profile, relayed afterwards. No
-- message is lost if the process stops between commit and delivery. event payload is the announced
-- fact; view is the compliance view as of that version, for the in-process projector only.
CREATE TABLE command.outbox
(
    event_id          UUID PRIMARY KEY,
    profile_id        UUID        NOT NULL,                       -- partition key, never published
    tenant_id         UUID        NOT NULL,
    customer_number   VARCHAR(64) COLLATE "C" NOT NULL,
    aggregate_version BIGINT      NOT NULL,
    event_type        VARCHAR(40) NOT NULL,
    payload           JSONB       NOT NULL,
    view              JSONB       NOT NULL,
    status            VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'DELIVERED')),
    attempts          INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    delivered_at      TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON command.outbox (created_at, aggregate_version) WHERE status = 'PENDING';
