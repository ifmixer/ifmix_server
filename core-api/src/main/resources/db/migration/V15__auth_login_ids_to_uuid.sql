-- V15__auth_login_ids_to_uuid.sql
-- login_install_id / login_app_id: VARCHAR(255) → UUID
-- 涉及表: auth_provider_identity, auth_device_secret, app_refresh_token

-- auth_provider_identity
ALTER TABLE auth_provider_identity
  ALTER COLUMN login_install_id TYPE UUID USING login_install_id::uuid;

ALTER TABLE auth_provider_identity
  ALTER COLUMN login_app_id TYPE UUID USING login_app_id::uuid;

-- auth_device_secret
ALTER TABLE auth_device_secret
  ALTER COLUMN login_install_id TYPE UUID USING login_install_id::uuid;

-- app_refresh_token
ALTER TABLE app_refresh_token
  ALTER COLUMN login_install_id TYPE UUID USING login_install_id::uuid;
