-- 表名去重：域前缀（core_<域>_）保留层级作用，域内单词不再用 _ 连接；
-- 关系表用 "2"（=to）连接；两张长名顺手简化（customer_scan_metrics→scanmetrics、scan_deep_research→deepresearch）。
-- 纯改名：无数据变更、无物理外键。实体 @Table 与原生 SQL 已同步。

ALTER TABLE core_ai_api_key                     RENAME TO core_ai_apikey;
ALTER TABLE core_ai_customer_scan_metrics       RENAME TO core_ai_scanmetrics;
ALTER TABLE core_ai_scan_collection             RENAME TO core_ai_scancollection;
ALTER TABLE core_ai_scan_collection_item        RENAME TO core_ai_scancollectionitem;
ALTER TABLE core_ai_scan_deep_research          RENAME TO core_ai_deepresearch;
ALTER TABLE core_ai_scan_record                 RENAME TO core_ai_scanrecord;
ALTER TABLE core_auth_identity_to_idpidentity_relation RENAME TO core_auth_identity2idp;
ALTER TABLE core_auth_install_attestation       RENAME TO core_auth_installattestation;
ALTER TABLE core_auth_install_customer_relation RENAME TO core_auth_install2customer;
ALTER TABLE core_auth_project_to_idp_relation   RENAME TO core_auth_project2idp;
ALTER TABLE core_cs_support_request             RENAME TO core_cs_supportrequest;
ALTER TABLE core_demo_todo_item                 RENAME TO core_demo_todoitem;
ALTER TABLE core_media_file_record              RENAME TO core_media_filerecord;
ALTER TABLE core_pay_store_notification         RENAME TO core_pay_storenotification;
ALTER TABLE core_project_config_revision        RENAME TO core_project_configrevision;
ALTER TABLE core_project_server_config          RENAME TO core_project_serverconfig;
