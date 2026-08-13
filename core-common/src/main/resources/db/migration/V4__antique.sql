-- V4__antique.sql
-- ScanRecord：古物扫描记录
CREATE TABLE IF NOT EXISTS scan_record (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    scan_id VARCHAR(255),
    image_url TEXT,
    result_json TEXT,
    status VARCHAR(32),
    tier VARCHAR(32),
    client_ip VARCHAR(45),
    related_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS scan_record_app_id_idx ON scan_record (app_id, id DESC);
CREATE INDEX IF NOT EXISTS scan_record_scan_id_idx ON scan_record (scan_id) WHERE scan_id IS NOT NULL;
