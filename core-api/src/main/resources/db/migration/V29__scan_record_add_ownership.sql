-- V29__scan_record_add_ownership.sql
ALTER TABLE ai_scan_record ADD COLUMN install_id UUID;
ALTER TABLE ai_scan_record ADD COLUMN user_id UUID;

CREATE INDEX IF NOT EXISTS ai_scan_record_install_id_idx ON ai_scan_record (app_id, install_id, id DESC) WHERE install_id IS NOT NULL;
