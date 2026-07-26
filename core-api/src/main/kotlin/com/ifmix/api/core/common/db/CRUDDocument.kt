package com.ifmix.api.core.common.db

import org.springframework.data.annotation.Id
import java.time.Instant

/** 所有文档的公共字段：id + 三个时间戳（含软删标记）。 */
abstract class CRUDDocument {
    @Id
    var id: String? = null
    var createdAt: Instant? = null
    var updatedAt: Instant? = null
    var deletedAt: Instant? = null
}
