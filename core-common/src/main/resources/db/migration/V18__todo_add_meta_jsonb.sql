-- Todo 增加 meta JSONB 字段（用于测试 Jimmer 对嵌套 JSON 局部更新行为）
ALTER TABLE core_todo ADD COLUMN meta jsonb;
