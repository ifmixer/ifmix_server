package com.ifmix.api.core.infra.db

import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.*

interface ClusterRouter {
    /** 按 appId 路由到集群 writer/reader 对 */
    fun forApp(appId: UUID): ClusterSqlPair
    /** 按 auth tenant 路由 */
    fun forTenant(tenantId: UUID): ClusterSqlPair
}

/** 当前实现（单集群）——多集群时替换为从 ClusterRegistry 查找对应 DataSource 的 KSqlClient。 */
@Component
class DefaultClusterRouter(private val registry: ClusterRegistry) : ClusterRouter {
    override fun forApp(appId: UUID) = ClusterSqlPair(registry.writerSql, registry.readerSql)
    override fun forTenant(tenantId: UUID) = ClusterSqlPair(registry.writerSql, registry.readerSql)
}
