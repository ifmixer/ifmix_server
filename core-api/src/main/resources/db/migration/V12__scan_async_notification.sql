-- V12: scan 异步化 + scan result notification。
-- 发布前预检（不要把旧 10/11 盲目改写）：
-- SELECT status, count(*) FROM public.core_ai_scan_record GROUP BY status ORDER BY status;
-- 已知旧语义：20=旧完成记录、30=旧失败记录；10/11 的处理以线上统计和 basic_result 是否为空为准。

ALTER TABLE public.core_ai_scan_record
    ADD COLUMN error_code character varying(64),
    ADD COLUMN error_details jsonb;

-- V12 前旧同步实现使用 20 表示完成、30 表示失败；新实现使用 20=IN_PROGRESS、30=SUCCESS、40=FAILED。
UPDATE public.core_ai_scan_record
SET status = CASE status
    WHEN 20 THEN 30
    WHEN 30 THEN 40
    ELSE status
END
WHERE status IN (20, 30);

ALTER TABLE public.core_ai_customer_scan_metrics
    ADD COLUMN pending_scan_count integer NOT NULL DEFAULT 0;
ALTER TABLE public.core_ai_customer_scan_metrics
    ALTER COLUMN pending_scan_count DROP DEFAULT;

ALTER TABLE public.core_install
    ADD COLUMN scan_result_noti_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN fcm_token_valid boolean NOT NULL DEFAULT true;
