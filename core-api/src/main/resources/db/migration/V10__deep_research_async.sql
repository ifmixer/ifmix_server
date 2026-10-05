-- V10: DeepResearch 异步化 + 历史版本（PG premium_result 存储；取代作废的 R2 方案）
-- 设计 docs/design/ai/deep-research-async.md §4

ALTER TABLE public.core_ai_scan_deep_research
    ADD COLUMN status smallint NOT NULL DEFAULT 30,   -- DEFAULT 仅为旧行回填
    ADD COLUMN error_code character varying(64),
    ADD COLUMN error_details jsonb;

-- 回填完成后去掉 DEFAULT：避免未来漏设 status 时静默生成 SUCCESS
ALTER TABLE public.core_ai_scan_deep_research ALTER COLUMN status DROP DEFAULT;

-- 按「列 + 唯一 + 非主键」定位删除旧唯一索引（V2 改表名未改索引名，不依赖硬编码名）
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT i.relname AS index_name
        FROM pg_class t
        JOIN pg_index ix ON t.oid = ix.indrelid
        JOIN pg_class i ON i.oid = ix.indexrelid
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
        WHERE t.relname = 'core_ai_scan_deep_research'
          AND a.attname = 'scan_record_id'
          AND ix.indisunique AND NOT ix.indisprimary
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS public.%I', r.index_name);
    END LOOP;
END $$;

CREATE INDEX ai_scan_deep_research_scan_record_created_idx
    ON public.core_ai_scan_deep_research USING btree (scan_record_id, created_at);

ALTER TABLE public.core_ai_scan_record
    ADD COLUMN latest_deep_research_id uuid;

-- 回填指针：每 scan 取 (created_at,id) 最新一条（DISTINCT ON，兼容潜在「一 scan 多条」）
UPDATE public.core_ai_scan_record r
SET latest_deep_research_id = d.id
FROM (
    SELECT DISTINCT ON (scan_record_id) id, scan_record_id
    FROM public.core_ai_scan_deep_research
    ORDER BY scan_record_id, created_at DESC, id DESC
) d
WHERE d.scan_record_id = r.id;
