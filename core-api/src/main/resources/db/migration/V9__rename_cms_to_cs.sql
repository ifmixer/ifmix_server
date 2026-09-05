-- V9: cms_feedback → cs_feedback（模块 cms 重命名为 cs = customer_support）。
-- 仅重命名表；pkey/约束名保留旧名不影响功能。
ALTER TABLE cms_feedback RENAME TO cs_feedback;
