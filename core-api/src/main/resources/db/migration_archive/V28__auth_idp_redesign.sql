-- Auth 重新设计：去掉 tenant 层，引入 IDP 模型
-- 删除: auth_tenant, auth_identity, auth_provider_identity, auth_device_secret, auth_refresh_token
-- 新增: auth_idp, auth_idpidentity, auth_app_to_idp_relation, auth_appuser_to_idpidentity_relation, auth_appuser_refreshtoken
-- 修改: app_config_revision 删 auth_tenant_id, user_appuser 删 auth_identity_id

-- Drop old tables (CASCADE：旧表间存在 FK 依赖，级联删除约束)
DROP TABLE IF EXISTS auth_device_secret CASCADE;
DROP TABLE IF EXISTS auth_provider_identity CASCADE;
DROP TABLE IF EXISTS auth_refresh_token CASCADE;
DROP TABLE IF EXISTS auth_identity CASCADE;
DROP TABLE IF EXISTS auth_tenant CASCADE;

-- app_config_revision: drop auth_tenant_id
ALTER TABLE app_config_revision DROP COLUMN IF EXISTS auth_tenant_id;

-- user_app_user: drop auth_identity_id（真实表名 user_app_user）
ALTER TABLE user_app_user DROP COLUMN IF EXISTS auth_identity_id;

-- auth_idp (全局)
CREATE TABLE auth_idp (
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    provider_type SMALLINT NOT NULL,
    third_id VARCHAR(500) NOT NULL,
    config JSONB NOT NULL DEFAULT '{}',
    "desc" TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

-- auth_idpidentity (全局)
CREATE TABLE auth_idpidentity (
    id UUID NOT NULL PRIMARY KEY,
    idp_id UUID NOT NULL,
    idp_identity_id VARCHAR(500) NOT NULL,
    email VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone VARCHAR(50),
    profile JSONB,
    login_ip VARCHAR(45),
    login_install_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX auth_idpidentity_idp_identity_idx ON auth_idpidentity (idp_id, idp_identity_id);

-- auth_app_to_idp_relation (app 级)
CREATE TABLE auth_app_to_idp_relation (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    idp_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX auth_app_to_idp_relation_app_idp_idx ON auth_app_to_idp_relation (app_id, idp_id);

-- auth_appuser_to_idpidentity_relation (app 级)
CREATE TABLE auth_appuser_to_idpidentity_relation (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    app_user_id UUID NOT NULL,
    idp_id UUID NOT NULL,
    idp_identity_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX auth_appuser_idpidentity_app_identity_idx ON auth_appuser_to_idpidentity_relation (app_id, idp_identity_id);
CREATE INDEX auth_appuser_idpidentity_app_user_idx ON auth_appuser_to_idpidentity_relation (app_id, app_user_id);

-- auth_appuser_refreshtoken (app 级)
CREATE TABLE auth_appuser_refreshtoken (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    app_user_id UUID NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    login_install_id UUID,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_appuser_refreshtoken_app_hash_idx ON auth_appuser_refreshtoken (app_id, token_hash);
