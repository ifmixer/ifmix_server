-- V2: actorType 改 Int（10=customer, 20=manager）；customer 加 merged_to_at、删 metadata。

-- 1. auth_appuser_refreshtoken.actor_type: varchar('customer') → smallint(10)
ALTER TABLE auth_appuser_refreshtoken ALTER COLUMN actor_type DROP DEFAULT;
ALTER TABLE auth_appuser_refreshtoken
    ALTER COLUMN actor_type TYPE SMALLINT
    USING (CASE actor_type WHEN 'customer' THEN 10 WHEN 'manager' THEN 20 ELSE 10 END);
ALTER TABLE auth_appuser_refreshtoken ALTER COLUMN actor_type SET DEFAULT 10;

-- 2. customer: 加 merged_to_at（合并发生时间，配合 merged_to）
ALTER TABLE customer ADD COLUMN merged_to_at TIMESTAMPTZ;

-- 3. customer: 删除不再需要的 metadata
ALTER TABLE customer DROP COLUMN IF EXISTS metadata;
