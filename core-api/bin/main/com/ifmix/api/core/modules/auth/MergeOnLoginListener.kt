package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.modules.antique.ScanRecordEntity
import com.ifmix.api.core.modules.iap.SubscriptionEntity
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 登录后归并匿名数据：把 (appId, installId, userId=null) 的 scan_record/subscription 回填 userId。
 * best-effort（错误隔离，不拖垮登录）；失败结构化 error 日志（便于告警/对账）。
 */
@Component
class MergeOnLoginListener(
    private val mongo: MongoTemplate,
    private val txRunner: TxRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onLogin(e: AuthLoggedInEvent) {
        val installId = e.installId ?: return
        try {
            txRunner.withTx(RequestContext(appId = e.appId, installId = installId)) {
                val q = Query(
                    Criteria.where("appId").`is`(e.appId)
                        .and("installId").`is`(installId).and("userId").`is`(null),
                )
                val u = Update().set("userId", e.appUserId).set("updatedAt", Instant.now())
                mongo.updateMulti(q, u, ScanRecordEntity::class.java)
                mongo.updateMulti(q, u, SubscriptionEntity::class.java)
            }
        } catch (ex: Exception) {
            log.error("mergeOnLogin failed appId={} appUserId={} installId={}", e.appId, e.appUserId, installId, ex)
        }
    }
}
