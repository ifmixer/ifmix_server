package com.ifmix.api.core.common.db

import org.springframework.data.mongodb.core.mapping.Document

/**
 * 测试用 Document，替代已被迁移到 Jimmer 的 TodoDocument。
 * 仅用于 CRUDRepository / CRUDAppRepository 的单元/集成测试。
 */
@Document(collection = "test_doc")
class TestDocument : BaseAppDocument() {
    var title: String? = null
}
