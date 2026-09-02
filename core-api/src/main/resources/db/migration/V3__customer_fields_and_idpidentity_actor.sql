-- V3: customer 补充资料列；auth_idpidentity 列调整（provider_type/可选 idp/手机分段/password）；
--     binding 表 actor 化重命名。

-- 1. customer: 新增资料列（含重新引入 metadata jsonb）
ALTER TABLE customer
    ADD COLUMN first_name            VARCHAR(255),
    ADD COLUMN last_name             VARCHAR(255),
    ADD COLUMN email                 VARCHAR(255),
    ADD COLUMN email_verified        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN phone_calling_code    VARCHAR(8),
    ADD COLUMN phone_country_code    VARCHAR(2),
    ADD COLUMN phone_national_number VARCHAR(32),
    ADD COLUMN phone_verified        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN metadata              JSONB;

-- 2. auth_idpidentity: 列改名 idp_identity_id → provider_subject_id
ALTER TABLE auth_idpidentity RENAME COLUMN idp_identity_id TO provider_subject_id;
ALTER INDEX auth_idpidentity_idp_identity_idx RENAME TO auth_idpidentity_subject_idx;

-- 3. auth_idpidentity: 新增 provider_type；idp_id 改可选；去掉 phone；加手机分段 + phone_verified + password
ALTER TABLE auth_idpidentity ADD COLUMN provider_type SMALLINT NOT NULL DEFAULT 30;
ALTER TABLE auth_idpidentity ALTER COLUMN provider_type DROP DEFAULT;
ALTER TABLE auth_idpidentity ALTER COLUMN idp_id DROP NOT NULL;
ALTER TABLE auth_idpidentity DROP COLUMN phone;
ALTER TABLE auth_idpidentity
    ADD COLUMN phone_calling_code    VARCHAR(8),
    ADD COLUMN phone_country_code    VARCHAR(2),
    ADD COLUMN phone_national_number VARCHAR(32),
    ADD COLUMN phone_verified        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN password              VARCHAR(255);

-- 4. binding: auth_appuser_to_idpidentity_relation → auth_actor_to_idpidentity_relation
--    删 customer 语义列 app_user_id；加 actor_type + actor_id
ALTER TABLE auth_appuser_to_idpidentity_relation RENAME TO auth_actor_to_idpidentity_relation;
ALTER TABLE auth_actor_to_idpidentity_relation
    RENAME CONSTRAINT auth_appuser_to_idpidentity_relation_pkey TO auth_actor_to_idpidentity_relation_pkey;
ALTER INDEX auth_appuser_idpidentity_app_identity_idx RENAME TO auth_actor_idpidentity_app_identity_idx;
ALTER INDEX auth_appuser_idpidentity_app_user_idx RENAME TO auth_actor_idpidentity_app_actor_idx;

ALTER TABLE auth_actor_to_idpidentity_relation ADD COLUMN actor_type SMALLINT NOT NULL DEFAULT 10;
ALTER TABLE auth_actor_to_idpidentity_relation ALTER COLUMN actor_type DROP DEFAULT;
-- 现有绑定均为 customer 主体，app_user_id 即 actor_id
ALTER TABLE auth_actor_to_idpidentity_relation RENAME COLUMN app_user_id TO actor_id;
