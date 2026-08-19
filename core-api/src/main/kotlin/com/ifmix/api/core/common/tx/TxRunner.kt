package com.ifmix.api.core.common.tx

import com.ifmix.api.core.common.http.OperationContext
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import org.springframework.stereotype.Component

/**
 * 事务边界接缝。业务通过 withTx 进入事务；跨多个函数的事务让它们在同一 withTx 内执行。
 * 使用 MongoDB ClientSession + withTransaction API。
 */
@Component
class TxRunner(private val mongoClient: MongoClient) {

    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        mongoClient.startSession().use { session ->
            return session.withTransaction { body(opCtx.withTx(session)) }
        }
    }
}
