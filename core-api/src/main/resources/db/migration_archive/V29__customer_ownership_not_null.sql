-- V29__customer_ownership_not_null.sql
-- Make install_id NOT NULL on all customer-owned tables.
-- Add user_id UUID where missing.

-- 1. ai_scan_record: add install_id + user_id (new columns)
ALTER TABLE ai_scan_record ADD COLUMN install_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000';
ALTER TABLE ai_scan_record ALTER COLUMN install_id DROP DEFAULT;
ALTER TABLE ai_scan_record ADD COLUMN user_id UUID;
CREATE INDEX IF NOT EXISTS ai_scan_record_install_id_idx ON ai_scan_record (app_id, install_id, id DESC);

-- 2. ai_scan_collection: install_id NOT NULL, user_id VARCHAR → UUID
UPDATE ai_scan_collection SET install_id = '00000000-0000-0000-0000-000000000000' WHERE install_id IS NULL;
ALTER TABLE ai_scan_collection ALTER COLUMN install_id SET NOT NULL;
ALTER TABLE ai_scan_collection ALTER COLUMN user_id TYPE UUID USING CASE WHEN user_id IS NOT NULL AND user_id != '' THEN user_id::uuid ELSE NULL END;

-- 3. demo_todo: install_id NOT NULL
UPDATE demo_todo SET install_id = '00000000-0000-0000-0000-000000000000' WHERE install_id IS NULL;
ALTER TABLE demo_todo ALTER COLUMN install_id SET NOT NULL;

-- 4. media_upload_record: install_id NOT NULL
UPDATE media_upload_record SET install_id = '00000000-0000-0000-0000-000000000000' WHERE install_id IS NULL;
ALTER TABLE media_upload_record ALTER COLUMN install_id SET NOT NULL;

-- 5. cms_feedback: add user preference columns
ALTER TABLE cms_feedback ADD COLUMN IF NOT EXISTS locale VARCHAR(16);
ALTER TABLE cms_feedback ADD COLUMN IF NOT EXISTS country VARCHAR(8);
ALTER TABLE cms_feedback ADD COLUMN IF NOT EXISTS currency VARCHAR(8);

-- 6. Rename lang → locale on tables that have the old column name
ALTER TABLE ai_scan_record RENAME COLUMN lang TO locale;
