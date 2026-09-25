-- V4: core_customer 增加两个累计计数列。
--
-- scan_count：用户成功完成一次普通扫描（saveNewScan）+1。
-- deep_research_count：用户成功完成一次深度研究（saveDeepResearch）+1。
-- 均为非负累计值，默认 0；由业务侧在成功写入事务内原子自增（scan_count = scan_count + 1）。

ALTER TABLE public.core_customer
    ADD COLUMN scan_count integer DEFAULT 0 NOT NULL,
    ADD COLUMN deep_research_count integer DEFAULT 0 NOT NULL;
