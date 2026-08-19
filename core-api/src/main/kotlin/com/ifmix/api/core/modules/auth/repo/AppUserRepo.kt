package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant
import com.ifmix.api.core.modules.auth.entity.AppUserEntity
import com.ifmix.api.core.modules.auth.repo.AppUserRepo

/**
 * app_user ensure：按 (appId, authIdentityId) 建/取，返回 appUserId。
 * 并发兜底：MongoDB 唯一索引冲突时 catch 并重试 findById。
 */
@Component
class AppUserRepo(private val mongo: MongoTemplate) {

    /** 为 (appId, authIdentityId) 建/取 app_user，返回 appUserId（hex string）。 */
    fun ensure(appId: String, authIdentityId: ObjectId): String {
        findId(appId, authIdentityId)?.let { return it }
        val now = Instant.now()
        val doc = AppUserEntity().apply {
            this.appId = ObjectId(appId); this.authIdentityId = authIdentityId; createdAt = now; updatedAt = now
        }
        return try {
            mongo.insert(doc); doc.id.toHexString()
        } catch (e: DuplicateKeyException) {
            findId(appId, authIdentityId) ?: throw e
        }
    }

    private fun findId(appId: String, authIdentityId: ObjectId): String? = mongo.findOne(
        Query(Criteria().andOperator(
            AppUserEntity::appId isEqualTo ObjectId(appId),
            AppUserEntity::authIdentityId isEqualTo authIdentityId,
        )),
        AppUserEntity::class.java,
    )?.id?.toHexString()
}
