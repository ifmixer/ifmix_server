-- V4: 身份模型改关系表。
--   删 auth_actor_to_idpidentity_relation（binding 移除）；
--   建 auth_identity（app 级账号：id + password）；
--   建 auth_identity_to_idpidentity_relation（auth_identity ↔ idp_identity 的 M:N）；
--   customer 加 auth_identity_id 列；
--   auth_idpidentity: provider_type → idp_type、删 password。

-- 1. 删除 actor 化 binding 表（改用 auth_identity + 关系表）
DROP TABLE IF EXISTS auth_actor_to_idpidentity_relation;

-- 2. auth_identity（app 级账号中枢，承载账号权威资料 + 密码）
CREATE TABLE auth_identity (
    id                    uuid        NOT NULL,
    app_id                uuid        NOT NULL,
    password              varchar(255),
    first_name            varchar(255),
    last_name             varchar(255),
    email                 varchar(255),
    email_verified        boolean     NOT NULL DEFAULT false,
    phone_calling_code    varchar(8),
    phone_country_code    varchar(2),
    phone_national_number varchar(32),
    phone_verified        boolean     NOT NULL DEFAULT false,
    metadata              jsonb,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT auth_identity_pkey PRIMARY KEY (id)
);
CREATE INDEX auth_identity_app_idx ON auth_identity USING btree (app_id);

-- 3. auth_identity_to_idpidentity_relation（M:N 关系表，app 级，软删）
CREATE TABLE auth_identity_to_idpidentity_relation (
    id               uuid        NOT NULL,
    app_id           uuid        NOT NULL,
    auth_identity_id uuid        NOT NULL,
    idp_identity_id  uuid        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    CONSTRAINT auth_identity_to_idpidentity_relation_pkey PRIMARY KEY (id)
);
-- 反查方向（idp_identity → 账号）：(app_id, idp_identity_id) 未删记录唯一
CREATE UNIQUE INDEX auth_identity_rel_app_idpidentity_idx
    ON auth_identity_to_idpidentity_relation USING btree (app_id, idp_identity_id)
    WHERE deleted_at IS NULL;
-- 正查方向（账号 → idp_identity 列表）
CREATE INDEX auth_identity_rel_app_authidentity_idx
    ON auth_identity_to_idpidentity_relation USING btree (app_id, auth_identity_id);

-- 4. customer 加 auth_identity_id（逻辑外键 → auth_identity；匿名未登录为 null）
ALTER TABLE customer ADD COLUMN auth_identity_id uuid;
CREATE INDEX customer_auth_identity_idx ON customer USING btree (app_id, auth_identity_id);

-- 5. auth_idpidentity: provider_type → idp_type、删 password
ALTER TABLE auth_idpidentity RENAME COLUMN provider_type TO idp_type;
ALTER TABLE auth_idpidentity DROP COLUMN password;

-- 6. auth_idp: provider_type → idp_type
ALTER TABLE auth_idp RENAME COLUMN provider_type TO idp_type;
