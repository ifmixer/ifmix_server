-- V33: premium_result 迁移到 ai_scan_deep_research 后，移除 ai_scan_record.premium_result

ALTER TABLE ai_scan_record DROP COLUMN IF EXISTS premium_result;
