package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.Hashing
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** 签发刷新令牌的结果。 */
data class RefreshIssued(val id: String, val token: String, val expiresAt: Instant)

/**
 * 刷新令牌管理：签发、按 hash 查找、原子旋转、吊销。
 */
@Component
class AppRefreshTokenRepo(private val mongo: MongoTemplate) {
    private val ttl = Duration.ofDays(30)

    /** 签发新刷新令牌。id 可预生成（供 refresh 原子轮换）。 */
    fun issue(appId: String, appUserId: String, deviceSecretId: String, loginInstallId: String?, id: String? = null): RefreshIssued {
        val token = Hashing.randomTokenBase64Url()
        val now = Instant.now()
        val exp = now.plus(ttl)
        val doc = AppRefreshTokenDocument().apply {
            if (id != null) this.id = id
            this.appId = appId; this.appUserId = appUserId; this.deviceSecretId = deviceSecretId
            tokenHash = Hashing.sha256Base64Url(token); this.loginInstallId = loginInstallId
            expiresAt = exp; createdAt = now; updatedAt = now
        }
        mongo.insert(doc)
        return RefreshIssued(doc.id, token, exp)
    }

    fun findByHash(appId: String, tokenPlain: String): AppRefreshTokenDocument? = mongo.findOne(
        Query(Criteria.where("appId").`is`(appId).and("tokenHash").`is`(Hashing.sha256Base64Url(tokenPlain))),
        AppRefreshTokenDocument::class.java,
    )

    /** 原子轮换：条件 updateFirst 只在未撤销时置 revokedAt+replacedBy。返回是否本方胜出。 */
    fun tryRotate(appId: String, tokenHash: String, newId: String): Boolean {
        val now = Instant.now()
        val res = mongo.updateFirst(
            Query(Criteria.where("appId").`is`(appId).and("tokenHash").`is`(tokenHash).and("revokedAt").`is`(null)),
            Update().set("revokedAt", now).set("replacedBy", newId).set("updatedAt", now),
            AppRefreshTokenDocument::class.java,
        )
        return res.modifiedCount > 0
    }

    fun revokeByAppUser(appId: String, appUserId: String) {
        val now = Instant.now()
        mongo.updateMulti(
            Query(Criteria.where("appId").`is`(appId).and("appUserId").`is`(appUserId).and("revokedAt").`is`(null)),
            Update().set("revokedAt", now).set("updatedAt", now),
            AppRefreshTokenDocument::class.java,
        )
    }

    /** 登出：撤销某 deviceSecret 关联的全部未撤销 refresh（跨该设备所有 app）。 */
    fun revokeByDeviceSecret(deviceSecretId: String) {
        val now = Instant.now()
        mongo.updateMulti(
            Query(Criteria.where("deviceSecretId").`is`(deviceSecretId).and("revokedAt").`is`(null)),
            Update().set("revokedAt", now).set("updatedAt", now),
            AppRefreshTokenDocument::class.java,
        )
    }
}
