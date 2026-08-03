package com.ifmix.api.core.infra.jimmer

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Test

class ClusterRegistryTest {

    private fun buildProps(): ClusterProperties {
        val ds = ClusterProperties.DataSourceProps(
            jdbcUrl = "jdbc:postgresql://localhost:5432/ifmix_core_local",
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
