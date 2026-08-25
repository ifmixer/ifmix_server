-- user_install: 设备安装记录
CREATE TABLE user_install (
    id            UUID        NOT NULL PRIMARY KEY,
    app_id        UUID        NOT NULL,
    platform      SMALLINT    NOT NULL,
    native_version VARCHAR(64),
    js_version    VARCHAR(64),
    client_ip     VARCHAR(45),
    info          JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_install_app_id ON user_install (app_id);
