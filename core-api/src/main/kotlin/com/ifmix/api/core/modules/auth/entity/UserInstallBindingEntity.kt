package com.ifmix.api.core.modules.auth.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * 设备绑定文档。
 *
 * 记录 (appId, userId, installId) 的设备-用户绑定关系，
 * 用于登录归并、设备统计和防作弊。
 */
@Document(collection = "core_user_install_binding")
class UserInstallBindingEntity : BaseAppEntity() {

    var userId: ObjectId? = null

    var installId: String? = null

    var firstSeenAt: Instant = Instant.now()

    var lastSeenAt: Instant = Instant.now()

    var loginCount: Int = 1

    var clientIp: String? = null

    var clientPlatform: String? = null
}
