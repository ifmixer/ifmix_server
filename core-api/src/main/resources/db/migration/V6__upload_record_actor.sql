-- V6: media_upload_record 去 category + customer_id，改主体无关模型 actor_type + actor_id。
-- actor_type: 10=customer / 20=manager（与 auth_refreshtoken 对齐）。
-- 历史行 customer_id 均为 customer 归属；customer_id 为 null 的旧行无归属，直接删（presign 审计记录，可重建）。

-- 1. 删无归属残行（customer_id 为 null，无法回填 actor_id NOT NULL）
DELETE FROM media_upload_record WHERE customer_id IS NULL;

-- 2. 加新列（先可空，回填后置 NOT NULL）
ALTER TABLE media_upload_record ADD COLUMN actor_id uuid;
ALTER TABLE media_upload_record ADD COLUMN actor_type smallint;

-- 3. 回填：历史归属均为 customer
UPDATE media_upload_record SET actor_id = customer_id, actor_type = 10;

-- 4. 置 NOT NULL
ALTER TABLE media_upload_record ALTER COLUMN actor_id SET NOT NULL;
ALTER TABLE media_upload_record ALTER COLUMN actor_type SET NOT NULL;

-- 5. 删旧列 + 旧索引
DROP INDEX IF EXISTS idx_upload_record_customer_id;
ALTER TABLE media_upload_record DROP COLUMN customer_id;
ALTER TABLE media_upload_record DROP COLUMN category;

-- 6. 新建 actor 归属索引（清理/合并按 actor_id + actor_type 过滤）
CREATE INDEX idx_upload_record_actor ON media_upload_record USING btree (actor_id, actor_type);
