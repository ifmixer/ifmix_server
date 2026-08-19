package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * collection：收藏夹。
 *
 * 每个用户（appId + userId/installId）最多一个默认夹。
 * partial unique 约束保证并发安全。
 */
@Document(collection = "collection")
@CompoundIndexes(
    CompoundIndex(name = "coll_app_install_idx", def = "{'appId': 1, 'installId': 1}"),
    CompoundIndex(
        name = "coll_default_uq",
        def = "{'appId': 1, 'installId': 1}",
        unique = true,
        partialFilter = "{ 'isDefault': true, 'deletedAt': null }",
    ),
)
class CollectionEntity : BaseAppEntity() {

    /** 安装标识（匿名场景下唯一标识用户）。 */
    var installId: String? = null

    /** 用户 ID（登录场景）。 */
    var userId: String? = null

    /** 是否为默认收藏夹。 */
    var isDefault: Boolean = false
}
