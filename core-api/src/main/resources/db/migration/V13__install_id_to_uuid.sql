-- V13__install_id_to_uuid.sql
-- install_id: VARCHAR -> UUID (core_collection, core_user_install_binding)

ALTER TABLE core_collection
  ALTER COLUMN install_id TYPE UUID USING install_id::uuid;

ALTER TABLE core_user_install_binding
  ALTER COLUMN install_id TYPE UUID USING install_id::uuid;
