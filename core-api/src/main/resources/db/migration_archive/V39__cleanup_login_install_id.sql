-- V39__cleanup_login_install_id.sql
-- 清理 install 残留：auth_idpidentity.login_install_id 列已随 install 主体废弃（实体去字段）。
-- 该列为可空 UUID，无索引/约束依赖（V28 redesign），直接 DROP。

ALTER TABLE auth_idpidentity DROP COLUMN IF EXISTS login_install_id;
