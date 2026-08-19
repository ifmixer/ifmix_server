package com.ifmix.api.core.common.db

import org.springframework.data.annotation.Id
import java.time.Instant

/** 所有文档的公共字段：id + 两个时间戳。appId/deletedAt/revision 为可选能力，见下方接口。 */
abstract class BaseEntity {
    @Id
    var id: String? = null
    var createdAt: Instant? = null
    var updatedAt: Instant? = null
}

/** 能力：多租户，按 appId 分片。实现后 CRUDRepository 自动注入 appId 过滤与创建盖章。 */
interface AppScoped {
    var appId: String?
}

/** 能力：软删。实现后 CRUDRepository 的删除走 deletedAt 标记，读写自动过滤 deletedAt=null。 */
interface SoftDeletable {
    var deletedAt: Instant?
}

/** 能力：版本化（追加式）。仅提供字段；版本化逻辑由各模块 repo（如 AppConfigRepo）实现。 */
interface Versioned {
    var revision: Int
}

/** 便利基类：覆盖"app 级 + 软删"最常见组合，字段写一次，避免每个文档重复 override。 */
abstract class BaseAppEntity : BaseEntity(), AppScoped, SoftDeletable {
    override var appId: String? = null
    override var deletedAt: Instant? = null
}
