-- attestation 表改为「带 proof 的请求必留痕」：
-- verify_status 区分验证结果（10=VALID 已绑定 / 20=INVALID 验证不通过 / 30=NOT_EVALUATED 服务端未评估；NULL=尚无结论），
-- status 仍是绑定生命周期（仅 VALID 行有意义；非绑定行写 40=NOT_BOUND，仅留痕）。
-- 唯一索引改为只约束 VALID 绑定：同一把 key 的失败尝试可重复留痕，不与绑定冲突。

ALTER TABLE core_auth_install_attestation ADD COLUMN verify_status INT NULL;   -- 验证结论：10/20/30；NULL=尚无结论（预留）
ALTER TABLE core_auth_install_attestation ADD COLUMN challenge TEXT NULL;   -- 原始 challengeStr，随行留痕供离线重验

DROP INDEX uk_install_attestation_subject;
CREATE UNIQUE INDEX uk_install_attestation_subject
    ON core_auth_install_attestation (project_id, provider, subject) WHERE subject IS NOT NULL AND verify_status = 10;
