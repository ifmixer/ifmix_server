-- V32: Deep Research
-- 1) ai_scan_record 增加 has_deep_search 标记
-- 2) 新增 ai_scan_deep_research 表，按 scan_record_id 一对一保存 premium_result

ALTER TABLE ai_scan_record
    ADD COLUMN IF NOT EXISTS has_deep_search BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS ai_scan_deep_research (
    id              UUID NOT NULL PRIMARY KEY,
    app_id          UUID NOT NULL,
    scan_record_id  UUID NOT NULL,
    premium_result  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 每个 scan_record 至多一条 deep research 记录（upsert 依据）
CREATE UNIQUE INDEX IF NOT EXISTS ai_scan_deep_research_scan_record_id_uidx
    ON ai_scan_deep_research (scan_record_id);
CREATE INDEX IF NOT EXISTS ai_scan_deep_research_app_id_idx
    ON ai_scan_deep_research (app_id, id DESC);
