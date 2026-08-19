package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.mybatis.MyBatisSessionFactories
import org.springframework.stereotype.Component

/**
 * SvcCtx 工厂 — 按场景提供构建方式，替代各 ModuleService 里散落的 private fun svc()。
 *
 * 核心逻辑：如果 opCtx.globalTxDsl 已存在（DataFetcher 开了全局事务），优先用它；否则走 router。
 * MyBatis 支持：可选注入 MyBatisSessionFactories，根据 isMutation 选择 writer/reader 工厂 openSession。
 */
@Component
class SvcCtxFactory(
    private val router: ClusterRouter,
    private val mybatis: MyBatisSessionFactories? = null,
) {

    /** 按 appId 路由（大多数模块用这个） */
    fun forApp(opCtx: OperationContext): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: router.forApp(opCtx.mustGetAppId()),
        session = mybatis?.let { factories ->
            val factory = if (opCtx.isMutation) factories.writer else factories.reader
            factory.openSession(true) // autoCommit=true；事务由 MyBatisTxRunner 管理
        },
        inTransaction = opCtx.inGlobalTx,
    )

    /** 按 auth tenant 路由 */
    fun forAuthTenant(opCtx: OperationContext, tenantId: java.util.UUID): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: router.forTenant(tenantId),
        session = mybatis?.let { factories ->
            // tenant 级别的操作默认走 writer
            factories.writer.openSession(true)
        },
        inTransaction = opCtx.inGlobalTx,
    )

    /** 默认（不需要路由参数，直接用 DEFAULT） */
    fun default(opCtx: OperationContext): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl,
        inTransaction = opCtx.inGlobalTx,
    )
}
