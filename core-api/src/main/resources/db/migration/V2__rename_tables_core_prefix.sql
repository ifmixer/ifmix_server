-- V2: 给所有业务表加 core_ 前缀。
--
-- 背景：统一表名前缀为 core_（core_{module}_{entity}），便于与未来其它服务/schema 区分。
-- 策略：仅 ALTER TABLE ... RENAME TO，不改列名/约束名/索引名/序列名（内部标识，不影响使用）。
-- 不改 Flyway 自身的 flyway_schema_history 表。
--
-- 幂等/安全：RENAME 不可重复执行（Flyway 版本机制保证只跑一次）；已上线但未正式使用，
-- 本机验证通过后再在线上执行。

ALTER TABLE public.ai_agnes_key                          RENAME TO core_ai_agnes_key;
ALTER TABLE public.ai_scan_collection                    RENAME TO core_ai_scan_collection;
ALTER TABLE public.ai_scan_collection_item               RENAME TO core_ai_scan_collection_item;
ALTER TABLE public.ai_scan_deep_research                 RENAME TO core_ai_scan_deep_research;
ALTER TABLE public.ai_scan_record                        RENAME TO core_ai_scan_record;

ALTER TABLE public.auth_identity                         RENAME TO core_auth_identity;
ALTER TABLE public.auth_identity_to_idpidentity_relation RENAME TO core_auth_identity_to_idpidentity_relation;
ALTER TABLE public.auth_idp                              RENAME TO core_auth_idp;
ALTER TABLE public.auth_idpidentity                      RENAME TO core_auth_idpidentity;
ALTER TABLE public.auth_project_to_idp_relation          RENAME TO core_auth_project_to_idp_relation;
ALTER TABLE public.auth_refreshtoken                     RENAME TO core_auth_refreshtoken;

ALTER TABLE public.cs_feedback                           RENAME TO core_cs_feedback;
ALTER TABLE public.cs_support_request                    RENAME TO core_cs_support_request;

ALTER TABLE public.customer                              RENAME TO core_customer;

ALTER TABLE public.demo_todo                             RENAME TO core_demo_todo;
ALTER TABLE public.demo_todo_item                        RENAME TO core_demo_todo_item;

ALTER TABLE public.media_upload_record                   RENAME TO core_media_upload_record;

ALTER TABLE public.pay_store_notification                RENAME TO core_pay_store_notification;
ALTER TABLE public.pay_subscription                      RENAME TO core_pay_subscription;

ALTER TABLE public.project_config_revision               RENAME TO core_project_config_revision;
ALTER TABLE public.project_info                          RENAME TO core_project_info;
