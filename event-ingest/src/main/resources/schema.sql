-- Event ingest inbox (SPEC 7.5.2). PostgreSQL, idempotent DDL.
-- One row per received canonical event envelope; event_id is the idempotency key.
CREATE TABLE IF NOT EXISTS inbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(96) NOT NULL,
    tenant_id UUID NOT NULL,
    business_book VARCHAR(16) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload_hash VARCHAR(64) NOT NULL,
    CONSTRAINT ck_inbox_event_status
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'DEAD_LETTERED')),
    CONSTRAINT ck_inbox_event_book
        CHECK (business_book IN ('DIRECT', 'ASSUMED')),
    CONSTRAINT ck_inbox_event_event_version
        CHECK (event_version >= 1),
    CONSTRAINT ck_inbox_event_aggregate_version
        CHECK (aggregate_version >= 1)
);

CREATE INDEX IF NOT EXISTS ix_inbox_event_received_at
    ON inbox_event (received_at DESC, event_id DESC);

CREATE INDEX IF NOT EXISTS ix_inbox_event_event_type
    ON inbox_event (event_type, received_at DESC);

CREATE INDEX IF NOT EXISTS ix_inbox_event_tenant
    ON inbox_event (tenant_id, received_at DESC);

-- Aggregate activity: tenant-scoped reads for one aggregate, newest first.
CREATE INDEX IF NOT EXISTS ix_inbox_event_tenant_aggregate_occurred
    ON inbox_event (tenant_id, aggregate_id, occurred_at DESC);
