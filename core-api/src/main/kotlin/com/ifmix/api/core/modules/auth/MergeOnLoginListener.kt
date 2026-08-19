package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.db.BaseEntity
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.modules.antique.entity.ScanRecordEntity
import com.ifmix.api.core.modules.iap.entity.SubscriptionEntity
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant
import com.ifmix.api.core.modules.auth.repo.UserInstallBindingRepo

/**
 * 登录后归并匿名数据：把 (appId, installId, userId=null) 的 scan_record/subscription 回填 userId。
 * best-effort（错误隔离，不拖垮登录）；失败结构化 error 日志（便于告警/对账）。
 * 同时通过 UserInstallBindingRepo 记录设备绑定信息。
 */
@Component
class MergeOnLoginListener(
    private val mongo: MongoTemplate,
    private val txRunner: TxRunner,
    private val bindingRepo: UserInstallBindingRepo,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onLogin(e: AuthLoggedInEvent) {
        val installId = e.installId ?: return

        // 记录设备绑定（含客户端 IP 和平台）
        try {
            val ctx = e.ctx ?: RequestContext(
                appId = e.appId,
                installId = installId,
                clientPlatform = e.clientPlatform?.let { com.ifmix.api.core.common.http.ClientPlatform.fromHeader(it) },
            )
            bindingRepo.upsert(
                ctx = ctx,
                userId = e.appUserId,
                installId = installId,
                clientIp = e.clientIp,
                clientPlatform = e.clientPlatform,
            )
        } catch (ex: Exception) {
            log.warn("bindingRepo.upsert failed appId={} installId={}", e.appId, installId, ex)
        }

        // 归并匿名 scan_record / subscription 到已登录用户
        try {
            txRunner.withTx(RequestContext(appId = e.appId, installId = installId)) {
                val q = Query(
                    Criteria().andOperator(
                        ScanRecordEntity::appId isEqualTo ObjectId(e.appId),
                        ScanRecordEntity::installId isEqualTo installId,
                        ScanRecordEntity::userId isEqualTo null,
                    ),
                )
                val u = Update().set(ScanRecordEntity::userId, e.appUserId)
                    .set(BaseEntity::updatedAt, Instant.now())
                mongo.updateMulti(q, u, ScanRecordEntity::class.java)
                mongo.updateMulti(q, u, SubscriptionEntity::class.java)
            }
        } catch (ex: Exception) {
            log.error("mergeOnLogin failed appId={} appUserId={} installId={}", e.appId, e.appUserId, installId, ex)
        }
    }
}
