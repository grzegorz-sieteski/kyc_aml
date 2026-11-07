-- The read side of the risk module (design section 9). Only the projector writes here; the readers
-- only select. Nothing in this schema can be joined back to the write side.
CREATE SCHEMA query;

-- The compliance view, one row per customer per booking centre. The view column holds every field of
-- spec query 1 as the projector wrote it; projection_version gates out-of-order and repeated events.
CREATE TABLE query.compliance_risk_view
(
    tenant_id          UUID                    NOT NULL,
    customer_number    VARCHAR(64) COLLATE "C" NOT NULL,
    projection_version BIGINT                  NOT NULL,
    view               JSONB                   NOT NULL,
    projected_at       TIMESTAMPTZ             NOT NULL,
    PRIMARY KEY (tenant_id, customer_number)
);

-- The front-office status. There is no column that could hold a score, a track, a restriction, a
-- critical fact, a decision or a justification, so tipping-off is impossible by schema (BR-40).
CREATE TABLE query.front_office_status
(
    tenant_id          UUID                    NOT NULL,
    customer_number    VARCHAR(64) COLLATE "C" NOT NULL,
    projection_version BIGINT                  NOT NULL,
    status             VARCHAR(30)             NOT NULL CHECK (status IN ('ACTIVE', 'VERIFICATION_PENDING')),
    projected_at       TIMESTAMPTZ             NOT NULL,
    PRIMARY KEY (tenant_id, customer_number)
);
