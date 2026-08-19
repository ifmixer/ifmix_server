package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.db.AppScoped
import com.ifmix.api.core.common.db.BaseEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/**
 * app 级用户（_id = 业务 userId）。唯一 (appId, authIdentityId)。
 */
@Document(collection = "app_user")
@CompoundIndex(name = "app_user_uq", def = "{'appId': 1, 'authIdentityId': 1}", unique = true)
class AppUserEntity : BaseEntity(), AppScoped {
    override var appId: String? = null
    var authIdentityId: String? = null
    var metadata: Map<String, Any?>? = null
}
