-- V14: 服务端专属项目配置表（不下发前端），存 FCM service account 等敏感凭据。
-- 与 core_project_config_revision（会下发客户端）分开，正是为了避免敏感凭据外泄。

CREATE TABLE public.core_project_server_config (
    id          uuid PRIMARY KEY,
    project_id  character varying(64) NOT NULL,
    fcm_config  jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 每 project 一行
CREATE UNIQUE INDEX core_project_server_config_project_id_uidx
    ON public.core_project_server_config (project_id);
