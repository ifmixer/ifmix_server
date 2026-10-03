-- V11: DeepResearch 记录增加 basic_result 快照列（统计分析用）
-- 每条 deep research 成功时，连同 premium_result 一起写入本次 AI 的 basicResult 快照，
-- 使 core_ai_scan_deep_research 表自给自足（basic + premium），无需回 join scan_record。

ALTER TABLE public.core_ai_scan_deep_research
    ADD COLUMN basic_result jsonb;
