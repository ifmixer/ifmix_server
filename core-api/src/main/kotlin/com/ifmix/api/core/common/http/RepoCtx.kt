package com.ifmix.api.core.common.http

import com.mongodb.ReadPreference
import com.mongodb.session.ClientSession
import com.ifmix.api.core.common.db.Cluster

/**
 * Repo-layer context. Pure DB info — no business semantics.
 * Built by Handler from ModuleCtx, passed to Repo methods.
 */
data class RepoCtx(
    val cluster: Cluster = Cluster.DEFAULT,
    val readPreference: ReadPreference = ReadPreference.primaryPreferred(),
    val txSession: ClientSession? = null,
    val inTransaction: Boolean = false,
) {
    companion object {
        /** Handler builds RepoCtx from ModuleCtx. */
        fun from(mc: ModuleCtx) = RepoCtx(
            cluster = mc.cluster,
            readPreference = mc.readPreference,
            txSession = mc.txSession,
            inTransaction = mc.inTransaction,
        )
    }
}
