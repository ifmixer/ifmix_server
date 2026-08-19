package com.ifmix.api.core.modules.auth.entity

import com.ifmix.api.core.common.db.BaseEntity
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * 设备+租户级引导钥匙。唯一 (authTenantId, secretHash)（分片键前缀）。稳定不轮换。
 */
@Document(collection = "auth_device_secret")
@CompoundIndexes(
    CompoundIndex(name = "device_secret_uq", def = "{'authTenantId': 1, 'secretHash': 1}", unique = true),
    CompoundIndex(name = "device_secret_identity_idx", def = "{'authTenantId': 1, 'authIdentityId': 1}"),
)
class AuthDeviceSecretEntity : BaseEntity() {
    var authTenantId: String? = null
    var authIdentityId: String? = null
    var secretHash: String? = null
    var loginInstallId: String? = null
    var expiresAt: Instant? = null
    var revokedAt: Instant? = null
    var lastUsedAt: Instant? = null
}
