-- V16: media 资源定名 file（与 actionName `m_media_file_*` 对齐），表同步改名（RENAME 保留数据）。
ALTER TABLE public.core_media_upload_record RENAME TO core_media_file_record;
