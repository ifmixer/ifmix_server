-- V14__scan_record_result_jsonb_drop_tier.sql
-- result_json: TEXT → JSONB（Jimmer @Serialized 映射）
-- 删除 tier 列（不再需要，权益信息通过 auth/me 获取）

ALTER TABLE core_scan_record
  ALTER COLUMN result_json TYPE JSONB USING result_json::jsonb;

ALTER TABLE core_scan_record
  DROP COLUMN IF EXISTS tier;
