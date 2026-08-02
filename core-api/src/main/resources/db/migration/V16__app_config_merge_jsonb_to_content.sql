-- V16__app_config_merge_jsonb_to_content.sql
-- 合并 apple_config / google_config / iap_config / wechat_config → content (JSONB)

ALTER TABLE core_app_config_version
  ADD COLUMN content JSONB NOT NULL DEFAULT '{}';

UPDATE core_app_config_version SET content = jsonb_build_object(
    'apple',  COALESCE(apple_config, '{}'::jsonb),
    'google', COALESCE(google_config, '{}'::jsonb),
    'iap',    COALESCE(iap_config, '{}'::jsonb),
    'wechat', COALESCE(wechat_config, '{}'::jsonb)
);

ALTER TABLE core_app_config_version DROP COLUMN apple_config;
ALTER TABLE core_app_config_version DROP COLUMN google_config;
ALTER TABLE core_app_config_version DROP COLUMN iap_config;
ALTER TABLE core_app_config_version DROP COLUMN wechat_config;
