package com.ifmix.api.core.infra.db

import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.*

interface ClusterRouter {
    /** 按 appId 路由 */
    fun forApp(appId: UUID): KSqlClient
    /** 按 auth tenant 路由 */
    fun forTenant(tenantId: UUID): KSqlClient
}

/** 当前实现（单集群）——多集群时替换为从 ClusterRegistry 查找对应 DataSource 的 KSqlClient。 */
@Component
class DefaultClusterRouter(private val sql: KSqlClient) : ClusterRouter {
    override fun forApp(appId: UUID) = sql
    override fun forTenant(tenantId: UUID) = sql
}
