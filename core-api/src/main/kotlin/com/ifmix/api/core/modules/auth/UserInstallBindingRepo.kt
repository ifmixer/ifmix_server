package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 设备绑定仓储。
 *
 * 按 (appId, userId, installId) 做 upsert：
 * - 不存在则插入新记录（loginCount=1）
 * - 已存在则递增 loginCount、刷新 lastSeenAt 及客户端信息
 */
@Component
class UserInstallBindingRepo(private val mongo: MongoTemplate) {

    /**
     * upsert 设备绑定记录。
     *
     * @param ctx            请求上下文（含 appId）
     * @param userId         用户 ID（可为 null，匿名绑定）
     * @param installId      安装 ID
     * @param clientIp       客户端 IP
     * @param clientPlatform 客户端平台（如 "ANDROID"、"IOS"、"WEB"）
     */
    fun upsert(
        ctx: RequestContext,
        userId: String?,
        installId: String,
        clientIp: String?,
        clientPlatform: String?,
    ) {
        val query = Query(
            Criteria().andOperator(
                UserInstallBindingDocument::appId isEqualTo ObjectId(ctx.appId),
                UserInstallBindingDocument::installId isEqualTo installId,
            ),
        )
        // 如果 userId 非空，也按 userId 精确匹配；否则只按 installId
        if (userId != null) {
            query.addCriteria(UserInstallBindingDocument::userId isEqualTo userId)
        }

        val existing = mongo.findOne(query, UserInstallBindingDocument::class.java)
        val now = Instant.now()

        if (existing != null) {
            // 更新已有记录
            val update = Update()
                .set(UserInstallBindingDocument::lastSeenAt, now)
                .inc(UserInstallBindingDocument::loginCount, 1)
                .set(UserInstallBindingDocument::clientIp, clientIp)
                .set(UserInstallBindingDocument::clientPlatform, clientPlatform)
                .set(BaseDocument::updatedAt, now)
            if (userId != null) {
                // 匿名登录后绑定用户，回填 userId
                update.set(UserInstallBindingDocument::userId, userId)
            }
            mongo.updateFirst(query, update, UserInstallBindingDocument::class.java)
        } else {
            // 插入新记录
            val doc = UserInstallBindingDocument().apply {
                appId = ObjectId(ctx.appId)
                this.userId = userId
                this.installId = installId
                firstSeenAt = now
                lastSeenAt = now
                loginCount = 1
                this.clientIp = clientIp
                this.clientPlatform = clientPlatform
                createdAt = now
                updatedAt = now
            }
            mongo.insert(doc)
        }
    }
}
