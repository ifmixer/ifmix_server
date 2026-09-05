-- V7: cms_feedback.category(单值 smallint) → reasons(smallint[] 多选)。
-- 反馈原因改为多选。已有单值数据包成单元素数组保留。

-- 1. 加新列 reasons smallint[]（先可空，回填后置 NOT NULL）
ALTER TABLE cms_feedback ADD COLUMN reasons smallint[];

-- 2. 回填：单值 category 包成单元素数组
UPDATE cms_feedback SET reasons = ARRAY[category]::smallint[] WHERE reasons IS NULL;

-- 3. 置 NOT NULL + 默认空数组（新行未显式给 reasons 时为空）
ALTER TABLE cms_feedback ALTER COLUMN reasons SET DEFAULT '{}';
ALTER TABLE cms_feedback ALTER COLUMN reasons SET NOT NULL;

-- 4. 删旧单值列
ALTER TABLE cms_feedback DROP COLUMN category;
