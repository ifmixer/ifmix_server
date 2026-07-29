-- V7__agnes_key.sql
-- agnes_keys: AI API keys 表
CREATE TABLE IF NOT EXISTS agnes_key (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    key VARCHAR(255) UNIQUE NOT NULL,
    email TEXT,
    type VARCHAR(32),  -- PRIMARY / FALLBACK / HOTSPARE
    rate_limit BIGINT DEFAULT -1,  # -1 means unlimited
    window_sec BIGINT DEFAULT 86400,  # default 24 hours in seconds
    models TEXT,
    unavailable_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS_agnes_key_app_id_idx ON agnes_key (app_id);
CREATE UNIQUE INDEX IF NOT EXISTS_agnes_key_app_key_unique_idx ON agnes_key (app_id, key) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS_agnes_key_unavailable_idx ON agnes_key (app_id, unavailable_at) WHERE unavailable_at IS NOT NULL AND deleted_at IS NULL;
