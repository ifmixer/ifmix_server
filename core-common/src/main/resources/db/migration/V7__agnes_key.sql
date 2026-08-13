-- V7__agnes_key.sql
-- agnes_key: AI API keys 表
CREATE TABLE IF NOT EXISTS agnes_key (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    key VARCHAR(512) NOT NULL,
    email VARCHAR(255),
    type VARCHAR(32),  -- PRIMARY / FALLBACK / HOTSPARE
    rate_limit BIGINT NOT NULL DEFAULT -1,  -- -1 means unlimited
    window_sec BIGINT NOT NULL DEFAULT 86400,  -- default 24 hours in seconds
    models TEXT,
    unavailable_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS agnes_key_uq ON agnes_key (key);
CREATE INDEX IF NOT EXISTS agnes_key_app_idx ON agnes_key (app_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS agnes_key_unavailable_idx ON agnes_key (app_id, unavailable_until) WHERE unavailable_until IS NOT NULL AND deleted_at IS NULL;
