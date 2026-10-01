-- V9: 扫描计数从 core_customer 迁出到 ai 模块自有表 core_ai_customer_scan_metrics。
--
-- 每 customer 至多一行（id = customer_id，确定性主键；customer_id 唯一，逻辑外键 → core_customer.id）；行不存在 = 两项计数均为 0，
-- 首次成功扫描时由业务侧 INSERT ... ON CONFLICT DO NOTHING 懒创建，再原子自增。
-- 回填只迁有计数的 customer（匿名 customer 绝大多数为 0，不建空行）。
--
-- core_customer.scan_count / deep_research_count 本次保留不删（实体已不再映射）：
-- 迁移与新代码发布之间旧实例仍会写旧列，确认发布完成后再由后续迁移删列。

CREATE TABLE IF NOT EXISTS public.core_ai_customer_scan_metrics (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    customer_id uuid NOT NULL,
    scan_count integer DEFAULT 0 NOT NULL,
    deep_research_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS core_ai_customer_scan_metrics_customer_uq
    ON public.core_ai_customer_scan_metrics USING btree (customer_id);

INSERT INTO public.core_ai_customer_scan_metrics
    (id, project_id, customer_id, scan_count, deep_research_count, created_at, updated_at)
SELECT c.id, c.project_id, c.id, c.scan_count, c.deep_research_count, now(), now()
FROM public.core_customer c
WHERE c.scan_count > 0 OR c.deep_research_count > 0
ON CONFLICT (customer_id) DO NOTHING;
