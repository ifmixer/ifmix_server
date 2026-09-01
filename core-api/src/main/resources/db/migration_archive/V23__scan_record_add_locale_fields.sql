-- V23__scan_record_add_locale_fields.sql
ALTER TABLE core_scan_record ADD COLUMN lang VARCHAR(16);
ALTER TABLE core_scan_record ADD COLUMN country VARCHAR(8);
ALTER TABLE core_scan_record ADD COLUMN currency VARCHAR(8);
