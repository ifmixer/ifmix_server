package com.ifmix.api.core.infra.db

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.*

interface ClusterRouter {
    /** 按 appId 路由 */
    fun forApp(appId: UUID): DSLContext
    /** 按 auth tenant 路由 */
    fun forTenant(tenantId: UUID): DSLContext
}

/** 当前实现（单集群）——多集群时替换为从 ClusterRegistry 查找对应 DataSource 的 DSLContext。 */
@Component
class DefaultClusterRouter : ClusterRouter {
    override fun forApp(appId: UUID) = SvcCtx.DEFAULT.dsl
    override fun forTenant(tenantId: UUID) = SvcCtx.DEFAULT.dsl
}
