-- V38__refresh_token_actor.sql
-- refresh token 表泛化（Medusa v2 actor 模型）：主体无关，以 actor_id + actor_type 关联。
-- app_user_id → actor_id；新增 actor_type（存量回填 'customer'）。
-- 实体类名本期暂留 AppUserRefreshToken（阶段 5 再改名），表名保持 auth_appuser_refreshtoken。

-- 1. app_user_id → actor_id
ALTER TABLE auth_appuser_refreshtoken RENAME COLUMN app_user_id TO actor_id;

-- 2. 新增 actor_type（NOT NULL DEFAULT 'customer'；存量记录即为 customer）
ALTER TABLE auth_appuser_refreshtoken ADD COLUMN actor_type VARCHAR NOT NULL DEFAULT 'customer';
