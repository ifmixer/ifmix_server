-- V10:
-- 1) 新增用户支持工单表 cs_support_request（意见反馈 / 联系我们）。
-- 2) 通用化 install_id：给现有 customer 侧数据表补 install_id 列（可空，仅记录用于分析）。

-- ── 1. 工单表 ─────────────────────────────────────────────────────────────
CREATE TABLE public.cs_support_request (
    id uuid NOT NULL,
    app_id uuid NOT NULL,
    install_id varchar(128),
    customer_id uuid,
    locale varchar(35),
    country varchar(2),
    currency varchar(3),
    title text NOT NULL,
    message text NOT NULL,
    email varchar(320),
    phone varchar(32),
    category smallint DEFAULT 0 NOT NULL,
    status smallint DEFAULT 10 NOT NULL,
    attachments jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    first_replied_at timestamp with time zone,
    last_agent_replied_at timestamp with time zone,
    last_customer_replied_at timestamp with time zone,
    resolved_at timestamp with time zone,
    closed_at timestamp with time zone,
    CONSTRAINT cs_support_request_pkey PRIMARY KEY (id)
);

-- 按 (app_id, customer_id) 查「我的工单」，按 created_at 倒序游标翻页。
CREATE INDEX ix_cs_support_request_owner
    ON public.cs_support_request (app_id, customer_id, id DESC);

-- ── 2. install_id 通用化 ─────────────────────────────────────────────────
ALTER TABLE public.ai_scan_record     ADD COLUMN install_id varchar(128);
ALTER TABLE public.ai_scan_collection ADD COLUMN install_id varchar(128);
ALTER TABLE public.cs_feedback        ADD COLUMN install_id varchar(128);
