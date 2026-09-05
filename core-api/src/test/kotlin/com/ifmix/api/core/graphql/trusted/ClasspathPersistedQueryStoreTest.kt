package com.ifmix.core.api.infra.graphql.trusted

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * ClasspathPersistedQueryStore 加载单测：指向 test 资源 customer.json。
 * 验证：合法 query 加载并可按 apiName 查到；不可解析 query 被跳过；未知返回 null。
 */
class ClasspathPersistedQueryStoreTest {

    private lateinit var store: ClasspathPersistedQueryStore

    @BeforeEach
    fun setup() {
        store = ClasspathPersistedQueryStore(
            allowlistPath = "classpath:graphql/test-persisted/",
            mapper = JsonMapper.builder().build(),
        )
        store.init()
    }

    @Test
    fun `loads valid persisted query by apiName`() {
        val entry = store.getByApiName("q_test_ok", "customer")
        assertThat(entry).isNotNull
        assertThat(entry!!.apiName).isEqualTo("q_test_ok")
        assertThat(entry.document).isNotNull()
    }

    @Test
    fun `skips unparsable query`() {
        assertThat(store.getByApiName("q_test_broken", "customer")).isNull()
    }

    @Test
    fun `unknown apiName returns null`() {
        assertThat(store.getByApiName("q_nope", "customer")).isNull()
    }
}
