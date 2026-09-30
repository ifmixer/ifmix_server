-- V6: Install ID 收敛 + 业务 install_id 可信 UUID 化。
--
-- 目标：core_install.id = API installId = JWT iid = relation.install_id = 业务表 install_id。
-- 兼容已执行的 V5（core_install 有独立 id 与 install_id 两列，业务表 install_id 为 varchar）。
--
-- 安全策略：
--  - core_install 主键收敛前做两项预检，任一冲突则整个迁移 ABORT（RAISE EXCEPTION），
--    绝不部分迁移、绝不跳过冲突行。
--  - 业务表 install_id 转 uuid 时不删除任何 resource;非法 legacy 值置 NULL 保留行。
--    存量 legacy NULL 只允许「可信回填」(回填出处可证的 token iid);无法可信回填的行保持 nullable 并延期,
--    绝不删除 resource、绝不静默清理。
--  - 转型后不加任何 CHECK:NOT VALID CHECK 仍会对后续 INSERT/UPDATE 强制校验,会阻断 legacy null 行的更新。
--    新写入非空由应用层(mustGetTokenInstallId)保证;NOT NULL 锁定推迟到未来 V7(须先完成可信回填,本轮不创建该文件)。

-- ============================================================
-- 1. core_install 主键收敛
-- ============================================================
DO $$
DECLARE
    dup_count bigint;
    cross_count bigint;
BEGIN
    -- 预检 1：install_id 全局重复（V5 唯一约束仅覆盖 (project_id, install_id)，不保证全局唯一）。
    SELECT count(*) INTO dup_count
    FROM (
        SELECT install_id FROM public.core_install GROUP BY install_id HAVING count(*) > 1
    ) d;
    IF dup_count > 0 THEN
        RAISE EXCEPTION 'V6 abort: % core_install.install_id value(s) are globally duplicated; cannot collapse to primary key', dup_count;
    END IF;

    -- 预检 2：交叉冲突——存在某行 id 等于另一行的 install_id（收敛后会主键撞车）。
    SELECT count(*) INTO cross_count
    FROM public.core_install a
    JOIN public.core_install b
      ON a.id = b.install_id
     AND a.id <> b.id;
    IF cross_count > 0 THEN
        RAISE EXCEPTION 'V6 abort: % cross-conflict(s) where one row.id equals another row.install_id', cross_count;
    END IF;
END $$;

-- 无冲突：把主键收敛到旧 token iid（install_id）。relation.install_id 已是该值，迁移后自然指向新 PK。
UPDATE public.core_install SET id = install_id WHERE id <> install_id;

-- 删除旧唯一索引与冗余列。
DROP INDEX IF EXISTS public.core_install_project_install_uq;
ALTER TABLE public.core_install DROP COLUMN IF EXISTS install_id;

-- ============================================================
-- 2. 业务表 install_id：varchar(128) -> nullable uuid
-- ============================================================
-- USING 表达式：合法 UUID 文本转 uuid,非法 legacy 值置 NULL(保留行,绝不删 resource)。
-- 仅做安全转型:不加任何 CHECK。NOT VALID CHECK (install_id IS NOT NULL) 虽不回扫存量行,
-- 但 PostgreSQL 会对其后所有 INSERT/UPDATE 生成的新行版本强制校验(见 sql-altertable NOTES:
-- "The constraint will still be applied against subsequent inserts or updates"),
-- 导致 legacy null install_id 行的任何 UPDATE(哪怕只改无关列)都失败。故此处不加约束。
-- 新写入非空由应用层保证(ActionContext.mustGetTokenInstallId,见 TrustedInstallIdWriteTest);
-- 存量 legacy NULL 只允许「可信回填」(回填可证来源的 token iid);无法可信回填的行保持 nullable、
-- 本轮不做任何清理,更不删除 resource。待可信回填完成后,才另建未来 V7 用预检 + SET NOT NULL 收尾锁定
-- (本轮不创建该 V7 文件)。

DO $$
DECLARE
    tbl text;
    tables text[] := ARRAY[
        'core_ai_scan_record',
        'core_ai_scan_collection',
        'core_cs_feedback',
        'core_cs_support_request'
    ];
BEGIN
    FOREACH tbl IN ARRAY tables LOOP
        EXECUTE format(
            'ALTER TABLE public.%I ALTER COLUMN install_id TYPE uuid USING (
                 CASE WHEN install_id ~* ''^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$''
                      THEN install_id::uuid ELSE NULL END
             )', tbl);
    END LOOP;
END $$;
