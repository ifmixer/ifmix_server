-- V1__baseline.sql
-- Jimmer 迁移基线：从 drizzle 项目导出的完整 schema（第一阶段：todo + feedback）
-- 表名规范：单数 + snake_case

CREATE TABLE IF NOT EXISTS todo (
    id UUID NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    app_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS todo_app_id_id_idx ON todo (app_id, id);

CREATE TABLE IF NOT EXISTS todo_item (
    id UUID NOT NULL PRIMARY KEY,
    todo_id UUID NOT NULL,
    app_id UUID NOT NULL,
    content VARCHAR(1000) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS todo_item_app_id_id_idx ON todo_item (app_id, id);
CREATE INDEX IF NOT EXISTS todo_item_demo_id_idx ON todo_item (todo_id);

CREATE TABLE IF NOT EXISTS feedback (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    install_id UUID NOT NULL,
    user_id UUID,
    scan_record_id UUID,
    category VARCHAR(32) NOT NULL,
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS feedback_app_created_idx ON feedback (app_id, created_at);
