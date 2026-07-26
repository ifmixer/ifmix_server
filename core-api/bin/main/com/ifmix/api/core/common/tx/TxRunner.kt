package com.ifmix.api.core.common.tx

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 事务边界接缝。业务通过 withTx 进入事务；跨多个函数的事务让它们在同一 withTx 内执行。
 * 当前 session 由 Spring 线程绑定管理，ctx 直通；未来多集群时改为在此注入手动 session。
 */
@Component
class TxRunner(txManager: MongoTransactionManager) {

    private val txTemplate = TransactionTemplate(txManager)

    fun <R> withTx(ctx: RequestContext, body: (RequestContext) -> R): R =
        txTemplate.execute { body(ctx) }!!
}
