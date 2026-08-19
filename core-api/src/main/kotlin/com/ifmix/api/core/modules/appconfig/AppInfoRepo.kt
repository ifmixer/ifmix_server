package com.ifmix.api.core.modules.appconfig

import com.ifmix.api.core.common.db.BaseEntity
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component

/** app_info 读取：按 appId（= _id）查稳定身份。 */
@Component
class AppInfoRepo(private val mongo: MongoTemplate) {

    fun findById(appId: String): AppInfoEntity? =
        if (ObjectId.isValid(appId)) mongo.findById(appId, AppInfoEntity::class.java) else null

    fun existsById(appId: String): Boolean =
        ObjectId.isValid(appId) && mongo.exists(
            Query(Criteria().andOperator(AppInfoEntity::id isEqualTo ObjectId(appId))),
            AppInfoEntity::class.java,
        )
}
