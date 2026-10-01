-- V8: AI key 表通用化——provider 无关的多来源 key 池。
--
-- core_ai_agnes_key 改名为 core_ai_api_key（沿用 V2 的 core_ 前缀惯例）。
-- provider 区分 key 来源：10=AGNES（码表 entity/ai/ApiProviders.kt）。
-- 存量行全部落为 AGNES（DEFAULT 10，无需 backfill）。
-- 不动 V1（含 3169 条 key 数据且已应用）；新环境走 V1 建旧名 → V8 改名，终态一致。

ALTER TABLE public.core_ai_agnes_key RENAME TO core_ai_api_key;

ALTER TABLE public.core_ai_api_key
    ADD COLUMN provider smallint DEFAULT 10 NOT NULL;

ALTER INDEX public.agnes_key_uq RENAME TO api_key_uq;
