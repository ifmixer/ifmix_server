package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * app_user ensure：按 (appId, authIdentityId) 建/取，返回 appUserId。
 * 并发兜底：MongoDB 唯一索引冲突时 catch 并重试 findById。
 */
@Component
class AppUserRepo(private val mongo: MongoTemplate) {

    /** 为 (appId, authIdentityId) 建/取 app_user，返回 appUserId。 */
    fun ensure(appId: String, authIdentityId: String): String {
        findId(appId, authIdentityId)?.let { return it }
        val now = Instant.now()
        val doc = AppUserEntity().apply {
            this.appId = appId; this.authIdentityId = authIdentityId; createdAt = now; updatedAt = now
        }
        return try {
            mongo.insert(doc); doc.id!!
        } catch (e: DuplicateKeyException) {
            findId(appId, authIdentityId) ?: throw e
        }
    }

    private fun findId(appId: String, authIdentityId: String): String? = mongo.findOne(
        Query(Criteria.where("appId").isEqualTo(appId).and("authIdentityId").isEqualTo(authIdentityId)),
        AppUserEntity::class.java,
    )?.id
}
