-- V5: install 设备表 + install↔customer 关系表。
-- install-id 由服务端生成（UuidV7）；关系表软删语义（deleted_at）+ (install_id, customer_id) 全局唯一。

CREATE TABLE IF NOT EXISTS public.core_install (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    install_id uuid NOT NULL,
    platform integer,
    device_info jsonb,
    app_version text,
    ota_version text,
    locale text,
    country text,
    currency text,
    reg_ip text,
    firebase_install_id text,
    fcm_token text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS core_install_project_install_uq
    ON public.core_install USING btree (project_id, install_id);

CREATE TABLE IF NOT EXISTS public.core_install_customer_relation (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    install_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone
);

-- 一对关系永远只有一行（复用行翻转 deleted_at）：全局唯一，不带 deleted_at 条件。
CREATE UNIQUE INDEX IF NOT EXISTS core_install_customer_rel_uq
    ON public.core_install_customer_relation USING btree (install_id, customer_id);

CREATE INDEX IF NOT EXISTS core_install_customer_rel_install_idx
    ON public.core_install_customer_relation USING btree (project_id, install_id);
