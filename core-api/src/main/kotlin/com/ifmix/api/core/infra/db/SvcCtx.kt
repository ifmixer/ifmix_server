package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.OperationContext
import org.apache.ibatis.session.SqlSession
import org.jooq.DSLContext

/**
 * 服务层上下文 — per-service-call，由 Service 构建。
 * 包含 operation 信息 + 当前集群的 DSLContext + 事务状态 + MyBatis SqlSession。
 */
data class SvcCtx(
    val op: OperationContext,
    val dsl: DSLContext,
    val session: SqlSession? = null,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) : AutoCloseable {
    companion object {
        /** 临时兼容：旧代码仍引用 DEFAULT。迁移完成后删除。 */
        lateinit var DEFAULT: SvcCtx
    }

    // ===== 便捷委托 =====
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache

    fun mustGetAppId() = op.mustGetAppId()
    fun mustGetUserId() = op.mustGetUserId()
    fun mustGetInstallId() = op.mustGetInstallId()

    /**
     * 获取 MyBatis Mapper 实例。
     * 要求 SvcCtx.session 非 null（即通过 SvcCtxFactory.forApp() 或 SvcCtxFactory.forAuthTenant() 构建）。
     */
    inline fun <reified M> mapper(): M =
        session?.getMapper(M::class.java)
            ?: error("SqlSession not available in this SvcCtx (session is null). Use SvcCtxFactory.forApp() to create SvcCtx with MyBatis support.")

    override fun close() {
        session?.close()
    }
}
