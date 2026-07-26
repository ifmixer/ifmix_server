package com.ifmix.api.core.common.db

/** 租户（app 级）文档基类：追加 appId。所有 app 级集合的文档继承它（数据字段复用）。 */
abstract class CRUDAppDocument : CRUDDocument() {
    var appId: String? = null
}
