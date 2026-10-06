-- V16: core_media_upload_record → core_media_file_record（media resource 改名 file，与 m_media_file_* 对齐）。
-- 纯表改名，不涉数据/结构变化。

ALTER TABLE public.core_media_upload_record RENAME TO core_media_file_record;
