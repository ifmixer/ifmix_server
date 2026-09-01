-- V36__customer_anonymous_model.sql
-- 前移合并：user_appuser → customer（去 user_ 前缀）；加匿名模型列；资源表归属列 user_id → customer_id。
-- 不考虑兼容，直接改。

-- 1. 主体表改名 user_app_user → customer（真实表名为 user_app_user，见 V27）
ALTER TABLE user_app_user RENAME TO customer;

-- 2. customer 加匿名模型列
ALTER TABLE customer ADD COLUMN anonymous BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE customer ADD COLUMN merged_to UUID;

-- 3. 存量回填：已绑定 IdP 身份（未删除 relation）的视为已登录，anonymous=false
UPDATE customer c
SET anonymous = false
WHERE EXISTS (
    SELECT 1 FROM auth_appuser_to_idpidentity_relation r
    WHERE r.app_user_id = c.id AND r.deleted_at IS NULL
);

-- 4. 资源表归属列 user_id → customer_id（同步重建相关索引名）

-- 4a. ai_scan_record（user_id 为阶段前新加的可空列）
ALTER TABLE ai_scan_record RENAME COLUMN user_id TO customer_id;

-- 4b. ai_scan_collection（user_id 已是 UUID）
ALTER TABLE ai_scan_collection RENAME COLUMN user_id TO customer_id;

-- 4c. media_upload_record
ALTER TABLE media_upload_record RENAME COLUMN user_id TO customer_id;
ALTER INDEX idx_upload_record_user_id RENAME TO idx_upload_record_customer_id;

-- 4d. demo_todo
ALTER TABLE demo_todo RENAME COLUMN user_id TO customer_id;
ALTER INDEX idx_core_demo_app_user RENAME TO idx_demo_todo_app_customer;

-- 4e. cms_feedback（也实现 CustomerOwnedProps；无独立 user_id 索引）
ALTER TABLE cms_feedback RENAME COLUMN user_id TO customer_id;

-- 5. pay_subscription 加归属列 customer_id（可空，存量留空）
ALTER TABLE pay_subscription ADD COLUMN customer_id UUID;
CREATE INDEX pay_subscription_app_customer_idx ON pay_subscription (app_id, customer_id);
