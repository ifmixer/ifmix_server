-- Rename table: core_app_config_version → core_app_config_revision
ALTER TABLE core_app_config_version RENAME TO core_app_config_revision;

-- Rename column: revision → revision_number
ALTER TABLE core_app_config_revision RENAME COLUMN revision TO revision_number;
