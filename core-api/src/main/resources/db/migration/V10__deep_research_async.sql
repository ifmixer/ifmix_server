-- V9: DeepResearch 异步化 + R2 存储 + 历史版本（设计 docs/superpowers/specs/2026-10-02-deep-research-async-r2-versioning-design.md §6）
--
-- core_ai_scan_deep_research：
--   status       任务状态 10=CREATED(预留)/20=IN_PROGRESS/30=SUCCESS/40=FAILED；旧数据回填 30
--   doc_version  doc JSON 结构版本（与 R2 doc 顶层 docVersion 一致）
--   file_key     结果 doc 的对象存储 key（R2 bucket u2）；SUCCESS 后写入，旧数据为 null（读取回退 premium_result 列）
--   error_code   稳定错误码：AI_FAILED/AI_STATUS_REJECTED/R2_UPLOAD_FAILED/TIMEOUT
--   error_details 结构化失败详情（AI_STATUS_REJECTED 时含 scan_status 供补拍）
-- 索引：去掉 scan_record_id 唯一索引（一 scan 多历史版本），改 (scan_record_id, created_at) 普通索引
-- core_ai_scan_record：latest_deep_research_id 权威指针（nullable，逻辑外键），旧数据回填为其唯一一条旧记录 id

ALTER TABLE public.core_ai_scan_deep_research
    ADD COLUMN status smallint NOT NULL DEFAULT 30,
    ADD COLUMN doc_version smallint NOT NULL DEFAULT 1,
    ADD COLUMN file_key character varying(512),
    ADD COLUMN error_code character varying(64),
    ADD COLUMN error_details jsonb;

DROP INDEX IF EXISTS ai_scan_deep_research_scan_record_id_uidx;

CREATE INDEX ai_scan_deep_research_scan_record_created_idx
    ON public.core_ai_scan_deep_research USING btree (scan_record_id, created_at);

ALTER TABLE public.core_ai_scan_record
    ADD COLUMN latest_deep_research_id uuid;

-- 旧数据一 scan 一条记录：指针直接回填
UPDATE public.core_ai_scan_record r
SET latest_deep_research_id = d.id
FROM public.core_ai_scan_deep_research d
WHERE d.scan_record_id = r.id;
