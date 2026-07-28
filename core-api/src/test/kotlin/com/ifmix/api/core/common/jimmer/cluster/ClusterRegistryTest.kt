package com.ifmix.api.core.common.jimmer.cluster

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs

class ClusterRegistryTest {

    private fun buildProps(): ClusterProperties {
        val ds = ClusterProperties.DataSourceProps(
            jdbcUrl = "jdbc:postgresql://localhost:5432/ifmix_core_local",
            username = "postgres",
            password = "postgres",
        )
        return ClusterProperties(
            clusters = mapOf("default" to ClusterProperties.ClusterProps(writer = ds, reader = ds)),
            clusterRouting = ClusterProperties.RoutingProps(mappings = mapOf("app-us" to "cluster-us")),
        )
    }

    @Test
    fun `forAppId returns default cluster when no mapping`() {
        val registry = ClusterRegistry(buildProps())
        registry.init()
        try {
            val client = registry.forAppId("unmapped-app")
            assertThat(client).isNotNull()
        } finally {
            registry.destroy()
        }
    }

    @Test
    fun `forAppId returns same client for same cluster`() {
        val registry = ClusterRegistry(buildProps())
        registry.init()
        try {
            val c1 = registry.forAppId("unmapped-1")
            val c2 = registry.forAppId("unmapped-2")
            assertThat(c1).isSameInstanceAs(c2)
        } finally {
            registry.destroy()
        }
    }

    @Test
    fun `forAppId throws when mapped cluster does not exist`() {
        val registry = ClusterRegistry(buildProps())
        registry.init()
        try {
            assertThrows<IllegalStateException> {
                registry.forAppId("app-us") // mapped to "cluster-us" which doesn't exist
            }
        } finally {
            registry.destroy()
        }
    }
}
