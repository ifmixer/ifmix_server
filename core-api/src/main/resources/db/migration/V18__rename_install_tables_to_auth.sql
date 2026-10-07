-- customer / install 相关表统一挂到 auth 域前缀（与 core_auth_identity / core_auth_idp 等一致）。
-- 仅改表名：无物理外键（逻辑外键 UUID），索引名不变仍可读；v1.0.6 未发布，无数据兼容负担。

ALTER TABLE core_customer                    RENAME TO core_auth_customer;
ALTER TABLE core_install                     RENAME TO core_auth_install;
ALTER TABLE core_install_customer_relation   RENAME TO core_auth_install_customer_relation;
ALTER TABLE core_install_attestation         RENAME TO core_auth_install_attestation;
