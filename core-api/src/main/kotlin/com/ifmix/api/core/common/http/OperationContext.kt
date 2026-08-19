package com.ifmix.api.core.common.http

import com.mongodb.ReadPreference
import com.mongodb.session.ClientSession
import org.bson.types.ObjectId

/**
 * Per-operation context. Built by DataFetcher from RequestContext + DFE metadata.
 * Passed to Facade as the sole parameter; Facade converts to ModuleCtx.
 */
data class OperationContext(
    val req: RequestContext,
    val opName: String? = null,
    val isMutation: Boolean = false,
    val txSession: ClientSession? = null,     // injected by TxRunner when transactional
    val inTransaction: Boolean = false,
) {
    val readCache get() = !isMutation
    val readPreference: ReadPreference get() =
        if (inTransaction) ReadPreference.primary() else
        if (isMutation) ReadPreference.primary() else ReadPreference.primaryPreferred()

    // convenience delegates
    val appId get() = req.appId
    val userId get() = req.userId
    val installId get() = req.installId
    val lang get() = req.lang
    val currency get() = req.currency
    val country get() = req.country
    val clientPlatform get() = req.clientPlatform

    fun mustGetUserId() = req.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
    fun mustGetInstallId() = req.installId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id required")

    /** TxRunner uses this to return a new OpCtx carrying the session. */
    fun withTx(session: ClientSession) = copy(txSession = session, inTransaction = true)

    companion object {
        fun from(req: RequestContext, isMutation: Boolean = false) = OperationContext(req = req, isMutation = isMutation)
    }
}
