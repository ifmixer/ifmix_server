-- V9__user_install_binding.sql
-- 记录用户与 install 的绑定关系，每次登录成功时 upsert。

CREATE TABLE IF NOT EXISTS core_user_install_binding (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    user_id UUID NOT NULL,
    install_id VARCHAR(255) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    login_count INT NOT NULL DEFAULT 1,
    client_ip VARCHAR(45),
    client_platform VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 核心查询：按 app + user 查所有 install
CREATE INDEX IF NOT EXISTS user_install_binding_app_user_idx
    ON core_user_install_binding (app_id, user_id, last_seen_at DESC);

-- 反查：按 app + install 查所有 user
CREATE INDEX IF NOT EXISTS user_install_binding_app_install_idx
    ON core_user_install_binding (app_id, install_id);

-- 幂等：同一 app 下同一 user + install 只有一条记录
CREATE UNIQUE INDEX IF NOT EXISTS user_install_binding_uq
    ON core_user_install_binding (app_id, user_id, install_id);
