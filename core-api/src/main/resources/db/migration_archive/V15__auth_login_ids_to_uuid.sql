-- V15__auth_login_ids_to_uuid.sql
-- login_install_id / login_app_id: VARCHAR(255) → UUID
-- 涉及表: auth_provider_identity, auth_device_secret, app_refresh_token

-- core_auth_provider_identity
ALTER TABLE core_auth_provider_identity
  ALTER COLUMN login_install_id TYPE UUID USING login_install_id::uuid;

ALTER TABLE core_auth_provider_identity
  ALTER COLUMN login_app_id TYPE UUID USING login_app_id::uuid;

-- core_auth_device_secret
ALTER TABLE core_auth_device_secret
  ALTER COLUMN login_install_id TYPE UUID USING login_install_id::uuid;

-- core_app_refresh_token
ALTER TABLE core_app_refresh_token
  ALTER COLUMN login_install_id TYPE UUID USING login_install_id::uuid;
