package com.ifmix.api.core.modules.auth.entity

import com.ifmix.api.core.common.db.AppScoped
import com.ifmix.api.core.common.db.BaseEntity
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * app 级不透明 refresh。唯一 (appId, tokenHash)（分片键前缀）。自有 revokedAt（非软删）。
 */
@Document(collection = "app_refresh_token")
@CompoundIndexes(
    CompoundIndex(name = "refresh_uq", def = "{'appId': 1, 'tokenHash': 1}", unique = true),
    CompoundIndex(name = "refresh_appuser_idx", def = "{'appId': 1, 'appUserId': 1}"),
    CompoundIndex(name = "refresh_device_idx", def = "{'deviceSecretId': 1}"),
)
class AppRefreshTokenEntity : BaseEntity(), AppScoped {
    override lateinit var appId: ObjectId
    var appUserId: ObjectId? = null
    var deviceSecretId: ObjectId? = null
    var tokenHash: String? = null
    var loginInstallId: String? = null
    var expiresAt: Instant? = null
    var revokedAt: Instant? = null
    var replacedBy: String? = null
}
