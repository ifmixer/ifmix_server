-- V37__drop_install.sql
-- 删除 install 主体：资源表去掉 install_id 归属列；drop install 表与其 relation 表。
-- 归属统一到 customer_id（V36 已建）。不考虑兼容，直接删。

-- 1. 资源表：先 drop 依赖 install_id 的索引，解除 NOT NULL，再 DROP COLUMN
--    （ai_scan_record / ai_scan_collection / media_upload_record / demo_todo / cms_feedback 均实现 CustomerOwnedProps）

-- 1a. ai_scan_record（install_id NOT NULL，索引 ai_scan_record_install_id_idx，V29）
DROP INDEX IF EXISTS ai_scan_record_install_id_idx;
ALTER TABLE ai_scan_record ALTER COLUMN install_id DROP NOT NULL;
ALTER TABLE ai_scan_record DROP COLUMN install_id;

-- 1b. ai_scan_collection（install_id NOT NULL，索引 collection_install_id_idx + 唯一 collection_default_uq，V5/V29）
DROP INDEX IF EXISTS collection_install_id_idx;
DROP INDEX IF EXISTS collection_default_uq;
ALTER TABLE ai_scan_collection ALTER COLUMN install_id DROP NOT NULL;
ALTER TABLE ai_scan_collection DROP COLUMN install_id;

-- 1c. media_upload_record（install_id 可空，无独立索引，V21）
ALTER TABLE media_upload_record DROP COLUMN install_id;

-- 1d. demo_todo（install_id 可空，部分索引 idx_core_demo_app_install，V17）
DROP INDEX IF EXISTS idx_core_demo_app_install;
ALTER TABLE demo_todo DROP COLUMN install_id;

-- 1e. cms_feedback（install_id NOT NULL，无独立索引，V1 baseline）
ALTER TABLE cms_feedback ALTER COLUMN install_id DROP NOT NULL;
ALTER TABLE cms_feedback DROP COLUMN install_id;

-- 2. drop install 主体表与其 relation 表
DROP TABLE IF EXISTS auth_appuser_to_install_relation;
DROP TABLE IF EXISTS user_install;
