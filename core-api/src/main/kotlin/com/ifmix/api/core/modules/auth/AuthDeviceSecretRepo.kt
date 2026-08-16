package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.Hashing
import com.ifmix.api.core.common.db.BaseDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 设备密钥管理：签发、查找、touch、吊销。
 */
@Component
class AuthDeviceSecretRepo(private val mongo: MongoTemplate) {
    private val idleTtl = Duration.ofDays(90)

    /** mint 新 device_secret，返回 (id, 明文 secret)。明文只此一次可见。 */
    fun issue(tenantId: String, authIdentityId: String, loginInstallId: String?): Pair<String, String> {
        val secret = Hashing.randomTokenBase64Url()
        val now = Instant.now()
        val doc = AuthDeviceSecretDocument().apply {
            authTenantId = tenantId; this.authIdentityId = authIdentityId
            secretHash = Hashing.sha256Base64Url(secret); this.loginInstallId = loginInstallId
            expiresAt = now.plus(idleTtl); lastUsedAt = now; createdAt = now; updatedAt = now
        }
        mongo.insert(doc)
        return doc.id.toHexString() to secret
    }

    /** 按 (tenantId, hash) 定向查有效 device_secret（未撤销、未过期）。 */
    fun findValid(tenantId: String, secretPlain: String): AuthDeviceSecretDocument? {
        val doc = mongo.findOne(
            Query(Criteria().andOperator(
                AuthDeviceSecretDocument::authTenantId isEqualTo tenantId,
                AuthDeviceSecretDocument::secretHash isEqualTo Hashing.sha256Base64Url(secretPlain),
            )),
            AuthDeviceSecretDocument::class.java,
        ) ?: return null
        val valid = doc.revokedAt == null && (doc.expiresAt?.isAfter(Instant.now()) == true)
        return if (valid) doc else null
    }

    fun touch(id: String) {
        val now = Instant.now()
        mongo.updateFirst(
            Query(Criteria().andOperator(AuthDeviceSecretDocument::id isEqualTo ObjectId(id))),
            Update().set(AuthDeviceSecretDocument::lastUsedAt, now)
                .set(AuthDeviceSecretDocument::expiresAt, now.plus(idleTtl))
                .set(BaseDocument::updatedAt, now),
            AuthDeviceSecretDocument::class.java,
        )
    }

    fun revoke(id: String) {
        val now = Instant.now()
        mongo.updateFirst(
            Query(Criteria().andOperator(AuthDeviceSecretDocument::id isEqualTo ObjectId(id))),
            Update().set(AuthDeviceSecretDocument::revokedAt, now).set(BaseDocument::updatedAt, now),
            AuthDeviceSecretDocument::class.java,
        )
    }
}
