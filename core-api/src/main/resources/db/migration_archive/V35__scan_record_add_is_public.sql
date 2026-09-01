-- V35: ai_scan_record 增加 is_public（是否公开），默认公开

ALTER TABLE ai_scan_record
    ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT true;
