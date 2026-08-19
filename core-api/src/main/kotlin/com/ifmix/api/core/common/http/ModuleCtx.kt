package com.ifmix.api.core.common.http

import com.mongodb.ReadPreference
import com.mongodb.session.ClientSession
import com.ifmix.api.core.common.db.Cluster

/**
 * Module-scoped context. Built by Facade from OperationContext; passed to Handler and Repo.
 * Carries module-specific cluster and transaction state.
 */
data class ModuleCtx(
    val op: OperationContext,
    val cluster: Cluster = Cluster.DEFAULT,
    val readCache: Boolean = op.readCache,
    val txSession: ClientSession? = op.txSession,
    val inTransaction: Boolean = op.inTransaction,
) {
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readPreference: ReadPreference get() =
        if (inTransaction) ReadPreference.primary() else op.readPreference
    fun mustGetUserId() = op.mustGetUserId()
    fun mustGetInstallId() = op.mustGetInstallId()

    companion object {
        fun from(opCtx: OperationContext, cluster: Cluster = Cluster.DEFAULT) = ModuleCtx(
            op = opCtx,
            cluster = cluster,
        )
    }
}
