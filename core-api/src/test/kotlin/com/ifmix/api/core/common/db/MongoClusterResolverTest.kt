package com.ifmix.api.core.infra.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate

@SpringBootTest
class MongoClusterResolverTest {

    @Autowired
    lateinit var resolver: MongoClusterResolver

    @Autowired
    lateinit var autoConfiguredTemplate: MongoTemplate

    @Test
    fun defaultResolverReturnsSingleTemplateRegardlessOfAppId() {
        assertThat(resolver.primary()).isSameAs(autoConfiguredTemplate)
        assertThat(resolver.forAppId("app-1")).isSameAs(autoConfiguredTemplate)
        assertThat(resolver.forAppId("app-2")).isSameAs(autoConfiguredTemplate)
    }
}
