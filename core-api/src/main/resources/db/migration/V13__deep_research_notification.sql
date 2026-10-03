-- V13: DeepResearch 独立完成通知开关。
ALTER TABLE public.core_install
    ADD COLUMN deep_research_noti_enabled boolean NOT NULL DEFAULT true;
