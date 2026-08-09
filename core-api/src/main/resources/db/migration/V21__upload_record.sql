-- V21__upload_record.sql
-- 记录每次 presignUpload 的信息，供后续校验/清理/统计使用。

CREATE TABLE core_upload_record (
    id              UUID            NOT NULL PRIMARY KEY,
    app_id          UUID            NOT NULL,
    install_id      UUID,
    user_id         UUID,
    object_key      TEXT            NOT NULL,
    content_type    VARCHAR(64)     NOT NULL,
    category        VARCHAR(64)     NOT NULL,
    client_ip       VARCHAR(64),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_upload_record_app_id ON core_upload_record (app_id);
CREATE INDEX idx_upload_record_user_id ON core_upload_record (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_upload_record_created_at ON core_upload_record (created_at);
