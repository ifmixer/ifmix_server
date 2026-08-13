package com.ifmix.api.core.customer.e2e

import com.ifmix.api.core.customer.e2e.support.E2eTestBase
import com.ifmix.api.core.customer.e2e.support.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired

/**
 * 收藏模块 E2E 测试。
 */
@DisplayName("Collection E2E")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CollectionE2eTest : E2eTestBase() {

    @Autowired
    lateinit var fixtures: TestFixtures

    @BeforeEach
    fun setup() {
        fixtures.seedMinimal()
    }

    @Test
    @Order(1)
    fun `get default collection creates one if not exists`() {
        put("/customer/query/core/collection/getDefaultCollection")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.isDefault").isEqualTo(true)
    }

    @Test
    @Order(2)
    fun `list items on empty collection returns empty`() {
        put("/customer/query/core/collection/findCollectionItemsByCursor")
            .bodyValue(mapOf("limit" to 10))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.items").isArray
            .jsonPath("$.data.hasMore").isEqualTo(false)
    }
}
