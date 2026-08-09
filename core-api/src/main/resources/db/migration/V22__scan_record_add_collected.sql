-- V22__scan_record_add_collected.sql
ALTER TABLE core_scan_record ADD COLUMN collected BOOLEAN NOT NULL DEFAULT false;
