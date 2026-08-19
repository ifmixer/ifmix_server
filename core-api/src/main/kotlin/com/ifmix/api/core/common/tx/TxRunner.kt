package com.ifmix.api.core.common.tx

import com.ifmix.api.core.common.http.OperationContext
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.TransactionBody
import org.springframework.stereotype.Component

/**
 * 事务边界接缝。业务通过 withTx 进入事务；跨多个函数的事务让它们在同一 withTx 内执行。
 * 使用 MongoDB ClientSession + withTransaction API。
 */
@Component
class TxRunner(private val mongoClient: MongoClient) {

    fun <R : Any> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        val session = mongoClient.startSession()
        return try {
            session.withTransaction(object : TransactionBody<R> {
                override fun execute(): R = body(opCtx.withTx(session))
            })
        } finally {
            session.close()
        }
    }
}
