package com.ifmix.api.core.graphql.common.trusted

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.io.InputStreamReader

/**
 * 从 classpath JSON 文件加载 persisted query allowlist。
 * 格式: { "sha256hash": { "name": "op_name", "query": "..." } }
 */
@Component
class PersistedQueryStore(private val objectMapper: ObjectMapper) {

    @Value("classpath:graphql/persisted-queries/customer.json")
    private lateinit var queriesResource: Resource

    private val allowedQueries: Map<String, QueryEntry> by lazy {
        val reader = queriesResource.inputStream.use { InputStreamReader(it) }
        objectMapper.readValue<Map<String, QueryEntry>>(reader)
    }

    data class QueryEntry(val name: String, val query: String)

    fun get(hash: String): String? = allowedQueries[hash]?.query

    fun contains(hash: String): Boolean = allowedQueries.containsKey(hash)

    fun getAllNames(): Set<String> = allowedQueries.values.map { it.name }.toSet()
}
