-- V34: 记录扫描结果所用的提示词版本

ALTER TABLE ai_scan_record
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(32) NOT NULL DEFAULT 'v10';

ALTER TABLE ai_scan_deep_research
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(32) NOT NULL DEFAULT 'v10';
