-- V15: install 平台证明（App Attest / Play Integrity）基础层。
-- core_project_server_config.app_attest_config：per-project 证明配置（JSONB，null = 关）。
-- core_install.store_type：安装来源商店（10=APP_STORE / 20=GOOGLE_PLAY），write-once，仅统计（规格 §5.9）。
-- core_install_attestation：只存 VALID 的长期凭证 / 绑定。DDL 逐条照抄规格 §5.4（含 v5 修订：
-- attestation_object 列 + idx_install_attestation_backfill 部分索引）。

ALTER TABLE core_project_server_config ADD COLUMN app_attest_config JSONB NULL;
ALTER TABLE core_install ADD COLUMN store_type INT NULL;   -- §5.9

CREATE TABLE core_install_attestation (
    id                    UUID PRIMARY KEY,          -- UuidV7（应用生成）
    project_id            TEXT NOT NULL,
    install_id            UUID NOT NULL,             -- 逻辑外键 core_install.id
    provider              INT  NOT NULL,             -- 110 APP_ATTEST / 120 PLAY_INTEGRITY
    subject               TEXT NULL,                 -- iOS keyId；Android NULL
    public_key            BYTEA NULL,
    attestation_object    BYTEA NULL,                -- 原始 attestation（≤16KB）；core-job 回填 receipt 成功后清空（§5.8）
    sign_count            BIGINT NOT NULL DEFAULT 0, -- iOS 首次 attestation 写 0；recover 的 `sign_count < :new` 依赖它不为 NULL
    receipt               BYTEA NULL,
    receipt_expires_at    TIMESTAMPTZ NULL,
    next_refresh_at       TIMESTAMPTZ NULL,
    refresh_failure_count INT NOT NULL DEFAULT 0,
    fraud_metric          INT NULL,
    signals               JSONB NOT NULL,
    evidence              JSONB NULL,                -- 90 天后清空（§5.7）
    status                INT NOT NULL,              -- 10 ACTIVE / 20 BLOCKED（风险封禁）/ 30 RETIRED（超出每个 install 的 ACTIVE 上限后轮换下来，不能 recover，也不能复活）
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    last_used_at          TIMESTAMPTZ NULL
);
CREATE UNIQUE INDEX uk_install_attestation_subject
    ON core_install_attestation (project_id, provider, subject) WHERE subject IS NOT NULL;
CREATE INDEX idx_install_attestation_install ON core_install_attestation (project_id, install_id);
CREATE INDEX idx_install_attestation_refresh ON core_install_attestation (next_refresh_at) WHERE receipt IS NOT NULL;
CREATE INDEX idx_install_attestation_backfill ON core_install_attestation (created_at) WHERE receipt IS NULL AND attestation_object IS NOT NULL;
CREATE INDEX idx_install_attestation_evidence ON core_install_attestation (created_at) WHERE evidence IS NOT NULL;
