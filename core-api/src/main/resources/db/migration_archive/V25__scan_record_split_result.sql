-- V25: 拆分 result_json 为 basic_result + premium_result
ALTER TABLE core_scan_record ADD COLUMN basic_result JSONB;
ALTER TABLE core_scan_record ADD COLUMN premium_result JSONB;

-- 迁移现有数据：将 result_json 内容复制到 basic_result
UPDATE core_scan_record SET basic_result = result_json WHERE result_json IS NOT NULL;

-- 删除旧列
ALTER TABLE core_scan_record DROP COLUMN result_json;
