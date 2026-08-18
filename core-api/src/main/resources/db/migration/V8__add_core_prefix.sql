-- V8__add_core_prefix.sql
-- 统一添加 core_ 前缀到所有表名，保持索引和 FK 约束不变（PostgreSQL ALTER TABLE RENAME 会自动级联）。

-- === V1 tables ===
ALTER TABLE todo RENAME TO core_todo;
ALTER TABLE todo_item RENAME TO core_demo_item;
ALTER TABLE feedback RENAME TO core_feedback;

-- === V2 tables ===
ALTER TABLE app_info RENAME TO core_app_info;
ALTER TABLE app_config RENAME TO core_app_config;

-- === V3 tables (注意顺序：先 rename 被引用的表，FK 会自动跟随) ===
ALTER TABLE auth_tenant RENAME TO core_auth_tenant;
ALTER TABLE auth_identity RENAME TO core_auth_identity;
ALTER TABLE auth_provider_identity RENAME TO core_auth_provider_identity;
ALTER TABLE app_user RENAME TO core_app_user;
ALTER TABLE auth_device_secret RENAME TO core_auth_device_secret;
ALTER TABLE app_refresh_token RENAME TO core_app_refresh_token;

-- === V4 tables ===
ALTER TABLE scan_record RENAME TO core_scan_record;

-- === V5 tables ===
ALTER TABLE collection RENAME TO core_collection;
ALTER TABLE collection_item RENAME TO core_collection_item;

-- === V6 tables ===
ALTER TABLE subscription RENAME TO core_subscription;
ALTER TABLE store_notification RENAME TO core_store_notification;

-- === V7 tables ===
ALTER TABLE agnes_key RENAME TO core_agnes_key;
