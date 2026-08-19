package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.Hashing
import com.ifmix.api.core.common.db.BaseEntity
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import com.ifmix.api.core.modules.auth.entity.AppRefreshTokenEntity

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
        val doc = AppRefreshTokenEntity().apply {
            if (id != null) this.id = ObjectId(id)
            this.appId = ObjectId(appId); this.appUserId = appUserId; this.deviceSecretId = deviceSecretId
            tokenHash = Hashing.sha256Base64Url(token); this.loginInstallId = loginInstallId
            expiresAt = exp; createdAt = now; updatedAt = now
        }
        mongo.insert(doc)
        return RefreshIssued(doc.id.toHexString(), token, exp)
    }

    fun findByHash(appId: String, tokenPlain: String): AppRefreshTokenEntity? = mongo.findOne(
        Query(Criteria().andOperator(
            AppRefreshTokenEntity::appId isEqualTo ObjectId(appId),
            AppRefreshTokenEntity::tokenHash isEqualTo Hashing.sha256Base64Url(tokenPlain),
        )),
        AppRefreshTokenEntity::class.java,
    )

    /** 原子轮换：条件 updateFirst 只在未撤销时置 revokedAt+replacedBy。返回是否本方胜出。 */
    fun tryRotate(appId: String, tokenHash: String, newId: String): Boolean {
        val now = Instant.now()
        val res = mongo.updateFirst(
            Query(Criteria().andOperator(
                AppRefreshTokenEntity::appId isEqualTo ObjectId(appId),
                AppRefreshTokenEntity::tokenHash isEqualTo tokenHash,
                AppRefreshTokenEntity::revokedAt isEqualTo null,
            )),
            Update().set(AppRefreshTokenEntity::revokedAt, now)
                .set(AppRefreshTokenEntity::replacedBy, newId)
                .set(BaseEntity::updatedAt, now),
            AppRefreshTokenEntity::class.java,
        )
        return res.modifiedCount > 0
    }

    fun revokeByAppUser(appId: String, appUserId: String) {
        val now = Instant.now()
        mongo.updateMulti(
            Query(Criteria().andOperator(
                AppRefreshTokenEntity::appId isEqualTo ObjectId(appId),
                AppRefreshTokenEntity::appUserId isEqualTo appUserId,
                AppRefreshTokenEntity::revokedAt isEqualTo null,
            )),
            Update().set(AppRefreshTokenEntity::revokedAt, now).set(BaseEntity::updatedAt, now),
            AppRefreshTokenEntity::class.java,
        )
    }

    /** 登出：撤销某 deviceSecret 关联的全部未撤销 refresh（跨该设备所有 app）。 */
    fun revokeByDeviceSecret(deviceSecretId: String) {
        val now = Instant.now()
        mongo.updateMulti(
            Query(Criteria().andOperator(
                AppRefreshTokenEntity::deviceSecretId isEqualTo deviceSecretId,
                AppRefreshTokenEntity::revokedAt isEqualTo null,
            )),
            Update().set(AppRefreshTokenEntity::revokedAt, now).set(BaseEntity::updatedAt, now),
            AppRefreshTokenEntity::class.java,
        )
    }
}
