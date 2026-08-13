-- V3__auth.sql
-- Auth 模块：认证租户、身份、Provider 身份、App 用户、设备密钥、Refresh Token

-- 认证租户（全局，不按 appId）
CREATE TABLE IF NOT EXISTS auth_tenant (
    id UUID NOT NULL PRIMARY KEY,
    jwt_private_key_pem TEXT,
    jwt_issuer VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 租户级用户身份
CREATE TABLE IF NOT EXISTS auth_identity (
    id UUID NOT NULL PRIMARY KEY,
    auth_tenant_id UUID NOT NULL REFERENCES auth_tenant(id),
    raw_email VARCHAR(255),
    email VARCHAR(255),
    raw_phone VARCHAR(50),
    phone VARCHAR(50),
    contact_email VARCHAR(255),
    display_name VARCHAR(255),
    password_hash VARCHAR(255),
    profile JSONB,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS auth_identity_tenant_idx ON auth_identity (auth_tenant_id, id);
CREATE INDEX IF NOT EXISTS auth_identity_tenant_email_idx ON auth_identity (auth_tenant_id, email);
CREATE INDEX IF NOT EXISTS auth_identity_tenant_phone_idx ON auth_identity (auth_tenant_id, phone);

-- Provider 身份（第三方登录）
CREATE TABLE IF NOT EXISTS auth_provider_identity (
    id UUID NOT NULL PRIMARY KEY,
    auth_tenant_id UUID NOT NULL REFERENCES auth_tenant(id),
    auth_identity_id UUID NOT NULL REFERENCES auth_identity(id),
    provider VARCHAR(32) NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone VARCHAR(50),
    user_metadata JSONB,
    provider_metadata JSONB,
    login_ip VARCHAR(45),
    login_install_id VARCHAR(255),
    login_app_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS auth_provider_uq
    ON auth_provider_identity (auth_tenant_id, provider, provider_account_id);
CREATE INDEX IF NOT EXISTS auth_provider_identity_idx
    ON auth_provider_identity (auth_tenant_id, auth_identity_id);

-- App 级用户
CREATE TABLE IF NOT EXISTS app_user (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    auth_identity_id UUID NOT NULL REFERENCES auth_identity(id),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS app_user_uq ON app_user (app_id, auth_identity_id);

-- 设备密钥（必须在 app_refresh_token 之前建，因为后者 FK 引用本表）
CREATE TABLE IF NOT EXISTS auth_device_secret (
    id UUID NOT NULL PRIMARY KEY,
    auth_tenant_id UUID NOT NULL REFERENCES auth_tenant(id),
    auth_identity_id UUID NOT NULL REFERENCES auth_identity(id),
    secret_hash VARCHAR(255) NOT NULL,
    login_install_id VARCHAR(255),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS device_secret_uq ON auth_device_secret (auth_tenant_id, secret_hash);
CREATE INDEX IF NOT EXISTS device_secret_identity_idx ON auth_device_secret (auth_tenant_id, auth_identity_id);

-- Refresh Token（依赖 app_user + auth_device_secret）
CREATE TABLE IF NOT EXISTS app_refresh_token (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    app_user_id UUID NOT NULL REFERENCES app_user(id),
    device_secret_id UUID REFERENCES auth_device_secret(id),
    token_hash VARCHAR(255) NOT NULL,
    login_install_id VARCHAR(255),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS refresh_token_uq ON app_refresh_token (app_id, token_hash);
CREATE INDEX IF NOT EXISTS refresh_appuser_idx ON app_refresh_token (app_id, app_user_id);
CREATE INDEX IF NOT EXISTS refresh_device_idx ON app_refresh_token (device_secret_id);
