-- V5: auth_appuser_refreshtoken → auth_refreshtoken（主体无关命名）。

ALTER TABLE auth_appuser_refreshtoken RENAME TO auth_refreshtoken;
ALTER TABLE auth_refreshtoken RENAME CONSTRAINT auth_appuser_refreshtoken_pkey TO auth_refreshtoken_pkey;
ALTER INDEX auth_appuser_refreshtoken_app_hash_idx RENAME TO auth_refreshtoken_app_hash_idx;
