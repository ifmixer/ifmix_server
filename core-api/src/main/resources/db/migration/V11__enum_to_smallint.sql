-- V11__enum_to_smallint.sql
-- 将枚举字段从 VARCHAR 转为 SMALLINT（Jimmer @EnumItem ordinal 映射）。
-- 编码规则：0 保留不用；同组内用十位连续（100,110,120）；不同组间隔百位（100,200,300）。

-- ============================================================
-- core_scan_record.status: VARCHAR -> SMALLINT
-- ============================================================
ALTER TABLE core_scan_record
  ALTER COLUMN status TYPE SMALLINT USING (
    CASE status
      WHEN 'PENDING'    THEN 100
      WHEN 'PROCESSING' THEN 110
      WHEN 'COMPLETED'  THEN 200
      WHEN 'FAILED'     THEN 300
      ELSE 100
    END
  ),
  ALTER COLUMN status SET NOT NULL,
  ALTER COLUMN status SET DEFAULT 100;

-- ============================================================
-- core_scan_record.tier: VARCHAR -> SMALLINT
-- ============================================================
ALTER TABLE core_scan_record
  ALTER COLUMN tier TYPE SMALLINT USING (
    CASE tier
      WHEN 'FREE'       THEN 100
      WHEN 'PRO'        THEN 200
      WHEN 'ENTERPRISE' THEN 300
      ELSE 100
    END
  ),
  ALTER COLUMN tier SET NOT NULL,
  ALTER COLUMN tier SET DEFAULT 100;

-- ============================================================
-- core_feedback.category: VARCHAR -> SMALLINT
-- ============================================================
ALTER TABLE core_feedback
  ALTER COLUMN category TYPE SMALLINT USING (
    CASE category
      WHEN 'LIKED'                THEN 100
      WHEN 'PRICE_TOO_HIGH'       THEN 200
      WHEN 'PRICE_TOO_LOW'        THEN 210
      WHEN 'PRICE_MISSING'        THEN 220
      WHEN 'WRONG_IDENTIFICATION' THEN 300
      WHEN 'FEATURE_REQUEST'      THEN 400
      WHEN 'MORE_RECOMMENDATIONS' THEN 410
      ELSE 100
    END
  ),
  ALTER COLUMN category SET NOT NULL,
  ALTER COLUMN category SET DEFAULT 100;

-- ============================================================
-- core_subscription.platform: VARCHAR -> SMALLINT
-- ============================================================
ALTER TABLE core_subscription
  ALTER COLUMN platform TYPE SMALLINT USING (
    CASE platform
      WHEN 'APPLE'  THEN 100
      WHEN 'GOOGLE' THEN 200
      ELSE 100
    END
  ),
  ALTER COLUMN platform SET NOT NULL,
  ALTER COLUMN platform SET DEFAULT 100;
