-- V17: core_demo_todo_item 补 note 列。
-- entity TodoItem.note（含 schema demo.graphqls 的 note 字段）已存在，建表迁移遗漏了该列，
-- 任何写入/读取 note 的操作都会报 column "note" does not exist。

ALTER TABLE public.core_demo_todo_item ADD COLUMN note text;
