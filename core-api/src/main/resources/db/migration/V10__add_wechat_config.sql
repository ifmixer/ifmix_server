-- V10: Add wechat_config JSONB column to core_app_config
ALTER TABLE core_app_config
    ADD COLUMN wechat_config JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN core_app_config.wechat_config IS '微信开放平台配置 {"appId":"wx...","appSecret":"..."}';
