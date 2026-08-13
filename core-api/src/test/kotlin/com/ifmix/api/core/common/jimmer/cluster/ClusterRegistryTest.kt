package com.ifmix.api.core.infra.jimmer

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Test

class ClusterRegistryTest {

    private fun buildProps(): AppDataSourceProperties {
        val ds = AppDataSourceProperties.DataSourceProps(
            jdbcUrl = "jdbc:postgresql://localhost:5432/ifmix_core_local",
            username = "postgres",
            password = "postgres",
        )
        val clusterProps = AppDataSourceProperties.ClusterProps(writer = ds, reader = ds)
        return AppDataSourceProperties(clusters = mapOf("default" to clusterProps))
    }

    @Test
    fun `sqlClient is singleton`() {
        val registry = ClusterRegistry(buildProps(), emptyList())
        try {
            val c1 = registry.sqlClient
            val c2 = registry.sqlClient
            assertThat(c1).isSameInstanceAs(c2)
        } finally {
            registry.destroy()
        }
    }

    @Test
    fun `flywayDataSource is created`() {
        val registry = ClusterRegistry(buildProps(), emptyList())
        try {
            assertThat(registry.flywayDataSource).isNotNull()
        } finally {
            registry.destroy()
        }
    }
}
