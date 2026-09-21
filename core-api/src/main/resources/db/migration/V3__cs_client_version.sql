-- V3: core_cs_feedback / core_cs_support_request 增加客户端版本快照列。
--
-- app_version：App 版本号（x-app-version），如 1.2.3
-- ota_version：热更新版本号（x-ota-version），形如 runtimeVersion-buildNumber-otaSeq，如 1-23-3
-- 均由客户端 header 上报，原样透传、可空，仅用于分析/回归定位（不用于鉴权）。

ALTER TABLE public.core_cs_feedback
    ADD COLUMN app_version character varying(64),
    ADD COLUMN ota_version character varying(64);

ALTER TABLE public.core_cs_support_request
    ADD COLUMN app_version character varying(64),
    ADD COLUMN ota_version character varying(64);
