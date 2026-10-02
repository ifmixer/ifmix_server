-- V10: DeepResearch 异步化 + R2 存储 + 历史版本（设计 docs/superpowers/specs/2026-10-02-deep-research-async-r2-versioning-design.md §6）
--
-- core_ai_scan_deep_research：
--   status       任务状态 10=CREATED(预留)/20=IN_PROGRESS/30=SUCCESS/40=FAILED；旧数据回填 30
--   doc_version  doc JSON 结构版本（与 R2 doc 顶层 docVersion 一致）
--   file_key     结果 doc 的对象存储 key（R2 bucket u2）；SUCCESS 后写入，旧数据为 null（读取回退 premium_result 列）
--   error_code   稳定错误码：AI_FAILED/AI_STATUS_REJECTED/R2_UPLOAD_FAILED/TIMEOUT
--   error_details 结构化失败详情（AI_STATUS_REJECTED 时含 scan_status 供补拍）
--
-- 索引：去掉 scan_record_id 唯一索引（一 scan 多历史版本），改 (scan_record_id, created_at) 普通索引。
-- 注意：V2 只 RENAME 表不改索引名（见 V2 头注释），故唯一索引仍叫旧名 ai_scan_deep_research_scan_record_id_uidx；
-- 为防个别环境索引名因历史操作漂移导致 DROP IF EXISTS 静默跳过（唯一索引残留会让第二次 insert 撞约束崩溃），
-- 这里按「列 + 非主键 + 唯一」定位删除，不依赖具体索引名。
--
-- core_ai_scan_record：latest_deep_research_id 权威指针（nullable，逻辑外键）。
-- 回填前提（设计 §6.5）：旧唯一约束下一 scan 只有一条记录；仍用 DISTINCT ON (created_at DESC, id DESC)
-- 取「本应成为 latest 的那条」，即使历史操作造成一 scan 多行也确定取最新，不依赖 PG 的任意选择。

ALTER TABLE public.core_ai_scan_deep_research
    ADD COLUMN status smallint NOT NULL DEFAULT 30,
    ADD COLUMN doc_version smallint NOT NULL DEFAULT 1,
    ADD COLUMN file_key character varying(512),
    ADD COLUMN error_code character varying(64),
    ADD COLUMN error_details jsonb;

-- 删除 scan_record_id 上的唯一索引（按列定位，主键除外）——解除「一 scan 一条」约束是本迁移的关键点
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
          AND ix.indisunique
          AND NOT ix.indisprimary
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS public.%I', r.index_name);
    END LOOP;
END $$;

CREATE INDEX ai_scan_deep_research_scan_record_created_idx
    ON public.core_ai_scan_deep_research USING btree (scan_record_id, created_at);

ALTER TABLE public.core_ai_scan_record
    ADD COLUMN latest_deep_research_id uuid;

-- 旧数据回填：一 scan 一条（唯一约束保证）；DISTINCT ON 兜底防历史异常多行时结果不确定
UPDATE public.core_ai_scan_record r
SET latest_deep_research_id = d.id
FROM (
    SELECT DISTINCT ON (scan_record_id) id, scan_record_id
    FROM public.core_ai_scan_deep_research
    ORDER BY scan_record_id, created_at DESC, id DESC
) d
WHERE d.scan_record_id = r.id;
