-- V7: core_customer 逻辑删除支持（账号注销）。
--
-- deleted_at: 软删时间（Jimmer @LogicalDeleted("now")；查询/更新自动过滤已删行）。
-- delete_reason_category: 删除原因分类编码（1=USER_REQUESTED 用户申请注销，见 DeletionReasons；null=未删除）。
-- delete_reason: 删除原因说明（客户端提交的原始文本，可空）。

ALTER TABLE public.core_customer
    ADD COLUMN deleted_at timestamp with time zone NULL,
    ADD COLUMN delete_reason_category integer NULL,
    ADD COLUMN delete_reason text NULL;
