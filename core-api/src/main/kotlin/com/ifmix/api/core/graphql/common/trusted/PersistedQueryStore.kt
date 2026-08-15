package com.ifmix.api.core.graphql.common.trusted

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct

/** Allowlist entry: human-readable name + full query text. */
data class PersistedQueryEntry(val name: String, val query: String)

/**
 * Allowlist 接口：按 (hash, bff) 查找预注册 query。
 * 生产实现可替换为 Redis 版，调用方代码不变。
 */
interface PersistedQueryStore {
    fun get(hash: String, bff: String): PersistedQueryEntry?
}

/**
 * 从 classpath JSON 加载 allowlist。POC 实现，生产环境替换为 Redis 版。
 *
 * 文件结构：
 *   graphql/persisted-queries/customer.json
 *   graphql/persisted-queries/admin.json
 *
 * 格式：{ "sha256hash": { "name": "...", "query": "..." } }
 */
@Component
class ClasspathPersistedQueryStore(
    @Value("\${graphql.trusted-documents.allowlist-path:classpath:graphql/persisted-queries/}")
    private val allowlistPath: String,
) : PersistedQueryStore {

    private val mapper = ObjectMapper()
    private val stores = mutableMapOf<String, Map<String, PersistedQueryEntry>>()

    @PostConstruct
    fun init() {
        val resolver = PathMatchingResourcePatternResolver()
        for (bff in listOf("customer", "admin")) {
            val resource = resolver.getResource("$allowlistPath${bff}.json")
            if (resource.exists()) {
                val root: JsonNode = mapper.readTree(resource.inputStream)
                val entries = mutableMapOf<String, PersistedQueryEntry>()
                root.properties().forEach { (hash, node) ->
                    entries[hash] = PersistedQueryEntry(
                        name = node.get("name")?.asString() ?: "",
                        query = node.get("query")?.asString() ?: "",
                    )
                }
                stores[bff] = entries
            } else {
                stores[bff] = emptyMap()
            }
        }
    }

    override fun get(hash: String, bff: String): PersistedQueryEntry? = stores[bff]?.get(hash)
}
