-- V12__schema_refactor.sql
-- ScanRecord: 删 scan_id/related_id, image_url → image_keys(JSONB), 加 user_display_name/user_notes
-- AppConfig → AppConfigVersion: 删 deleted_at/updated_at, 加 enabled/slug
-- AgnesKey: 加 type (SMALLINT), 删 type (VARCHAR)
-- AppInfo: slug NOT NULL

-- === ScanRecord ===
ALTER TABLE core_scan_record DROP COLUMN IF EXISTS scan_id;
ALTER TABLE core_scan_record DROP COLUMN IF EXISTS related_id;

-- image_url → image_keys (JSONB)
ALTER TABLE core_scan_record ADD COLUMN image_keys JSONB NOT NULL DEFAULT '[]';
-- 迁移旧数据: 将 image_url 转为 JSONB 数组
UPDATE core_scan_record SET image_keys =
    CASE WHEN image_url IS NOT NULL AND image_url != ''
         THEN jsonb_build_array(jsonb_build_object('key', image_url))
         ELSE '[]'::jsonb
    END;
ALTER TABLE core_scan_record DROP COLUMN IF EXISTS image_url;

-- 加用户编辑字段
ALTER TABLE core_scan_record ADD COLUMN user_display_name VARCHAR(255);
ALTER TABLE core_scan_record ADD COLUMN user_notes TEXT;

-- === AppConfig → AppConfigVersion ===
ALTER TABLE core_app_config RENAME TO core_app_config_version;
ALTER TABLE core_app_config_version DROP COLUMN IF EXISTS deleted_at;
ALTER TABLE core_app_config_version DROP COLUMN IF EXISTS updated_at;
ALTER TABLE core_app_config_version ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE core_app_config_version ADD COLUMN slug VARCHAR(64) NOT NULL DEFAULT '';

-- === AgnesKey: 旧 type 列 (VARCHAR) → 新 type 列 (SMALLINT) ===
ALTER TABLE core_agnes_key DROP COLUMN IF EXISTS type;
ALTER TABLE core_agnes_key ADD COLUMN type SMALLINT NOT NULL DEFAULT 100;

-- === AppInfo.slug NOT NULL ===
UPDATE core_app_info SET slug = id::text WHERE slug IS NULL;
ALTER TABLE core_app_info ALTER COLUMN slug SET NOT NULL;
