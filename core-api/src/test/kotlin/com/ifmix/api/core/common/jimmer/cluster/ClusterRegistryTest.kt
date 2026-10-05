package com.ifmix.core.api.infra.jimmer

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClusterRegistryTest {

    companion object {
        /** 本地开发库（Hikari 懒初始化，取连接即建池）——不可达时整组跳过，不污染 CI/无环境机器。 */
        private const val LOCAL_DB = "localhost"
        private const val LOCAL_DB_PORT = 5432

        /** 探测本测试真正要用的库（url/账密全等，2s 超时）——只查端口开着不够：库不存在/账密错时同样跑不了。 */
        private fun localDbReachable(): Boolean = runCatching {
            java.sql.DriverManager.getConnection(
                "jdbc:postgresql://$LOCAL_DB:$LOCAL_DB_PORT/ifmix_core_local?connectTimeout=2",
                "postgres", "postgres",
            ).use { }
        }.isSuccess
    }

    @BeforeEach
    fun requireLocalDb() = assumeTrue(localDbReachable(), "需本地库 $LOCAL_DB:$LOCAL_DB_PORT")

    private fun buildProps(): ClusterProperties {
        val ds = ClusterProperties.DataSourceProps(
            jdbcUrl = "jdbc:postgresql://$LOCAL_DB:$LOCAL_DB_PORT/ifmix_core_local",
            username = "postgres",
            password = "postgres",
        )
        return ClusterProperties(writer = ds, reader = ds)
    }

    @Test
    fun `sqlClient is singleton`() {
        val registry = ClusterRegistry(buildProps(), emptyList(), false)
        try {
            val c1 = registry.sqlClient
            val c2 = registry.sqlClient
            assertThat(c1).isSameInstanceAs(c2)
        } finally {
            registry.destroy()
        }
    }

    @Test
    fun `routingDataSource is created`() {
        val registry = ClusterRegistry(buildProps(), emptyList(), false)
        try {
            assertThat(registry.routingDataSource).isNotNull()
        } finally {
            registry.destroy()
        }
    }
}
