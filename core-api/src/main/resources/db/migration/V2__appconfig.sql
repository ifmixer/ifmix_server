-- V2__appconfig.sql
CREATE TABLE IF NOT EXISTS app_info (
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    slug VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS app_info_slug_uq ON app_info (slug) WHERE slug IS NOT NULL;

CREATE TABLE IF NOT EXISTS app_config (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    auth_tenant_id UUID,
    apple_bundle_id VARCHAR(255),
    android_package_name VARCHAR(255),
    apple_config JSONB NOT NULL DEFAULT '{}',
    google_config JSONB NOT NULL DEFAULT '{}',
    iap_config JSONB NOT NULL DEFAULT '{}',
    revision INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS app_config_current_uq ON app_config (app_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS app_config_bundle_idx ON app_config (apple_bundle_id) WHERE apple_bundle_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS app_config_package_idx ON app_config (android_package_name) WHERE android_package_name IS NOT NULL;
