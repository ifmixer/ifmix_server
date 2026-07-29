-- V5__collection.sql
-- collection: 收藏夹表
CREATE TABLE IF NOT EXISTS collection (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    install_id VARCHAR(255),
    user_id VARCHAR(255),
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS collection_app_id_idx ON collection (app_id, id DESC);
CREATE INDEX IF NOT EXISTS collection_install_id_idx ON collection (app_id, install_id);
CREATE UNIQUE INDEX IF NOT EXISTS collection_default_unique_idx ON collection (app_id, install_id) WHERE is_default = TRUE AND deleted_at IS NULL;

-- collection_item: 收藏条目（关联表）
CREATE TABLE IF NOT EXISTS collection_item (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    collection_id UUID NOT NULL,
    scan_record_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS collection_item_app_collection_idx ON collection_item (app_id, collection_id, id DESC);
CREATE INDEX IF NOT EXISTS collection_item_scan_record_idx ON collection_item (app_id, scan_record_id);
CREATE UNIQUE INDEX IF NOT EXISTS collection_item_scan_record_unique_idx ON collection_item (app_id, collection_id, scan_record_id) WHERE deleted_at IS NULL;
