-- 去掉 core_ 前缀，统一加模块名前缀
-- payment → pay, storage → media

-- demo
ALTER TABLE core_todo RENAME TO demo_todo;
ALTER TABLE core_todo_item RENAME TO demo_todo_item;

-- app
ALTER TABLE core_app_config_revision RENAME TO app_config_revision;
ALTER TABLE core_app_info RENAME TO app_info;

-- auth
ALTER TABLE core_auth_tenant RENAME TO auth_tenant;
ALTER TABLE core_auth_device_secret RENAME TO auth_device_secret;
ALTER TABLE core_app_user RENAME TO user_app_user;
ALTER TABLE core_user_install_binding RENAME TO auth_appuser_to_install_relation;
ALTER TABLE core_auth_identity RENAME TO auth_identity;
ALTER TABLE core_auth_provider_identity RENAME TO auth_provider_identity;
ALTER TABLE core_app_refresh_token RENAME TO auth_refresh_token;

-- pay (原 payment)
ALTER TABLE core_store_notification RENAME TO pay_store_notification;
ALTER TABLE core_subscription RENAME TO pay_subscription;

-- media (原 storage)
ALTER TABLE core_upload_record RENAME TO media_upload_record;

-- cms
ALTER TABLE core_feedback RENAME TO cms_feedback;

-- ai
ALTER TABLE core_agnes_key RENAME TO ai_agnes_key;
ALTER TABLE core_scan_collection RENAME TO ai_scan_collection;
ALTER TABLE core_scan_record RENAME TO ai_scan_record;
ALTER TABLE core_scan_collection_item RENAME TO ai_scan_collection_item;
