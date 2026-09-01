-- Todo 增加用户归属字段：匿名用户只有 installId，登录用户有 userId
ALTER TABLE core_todo ADD COLUMN install_id UUID;
ALTER TABLE core_todo ADD COLUMN user_id UUID;

-- 加索引：按 appId + userId 或 appId + installId 查询
CREATE INDEX idx_core_demo_app_user ON core_todo (app_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_core_demo_app_install ON core_todo (app_id, install_id) WHERE install_id IS NOT NULL;
