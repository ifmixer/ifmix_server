package com.ifmix.core.api.infra.graphql.trusted

import graphql.language.Document
import graphql.parser.Parser
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Trusted Document（persisted query）条目：reqName + 原始 query 文本 + 预解析的 Document。
 *
 * Document 在加载时解析一次并缓存，运行时 PreparsedDocumentProvider 直接复用（免重复 parse）。
 */
data class PersistedQueryEntry(
    val reqName: String,
    val query: String,
    val document: Document,
)

/**
 * Persisted query allowlist 存储：按 (reqName, bff) 查预注册 query。
 *
 * reqName = 前端约定的 API 标识（GReq path 末段值），格式 `${q|m}_${module}_${action}`，
 * 如 `q_ai_findMyScanById`。它是 manifest 的 key，全局唯一，不必等于 GraphQL 顶层 field name。
 *
 * 接口抽象，当前 classpath JSON 实现，后续可换 Redis 而不动调用方。
 */
interface PersistedQueryStore {
    fun getByReqName(reqName: String, bff: String): PersistedQueryEntry?
}

/**
 * 从 classpath JSON 加载 allowlist。
 *
 * 文件：`graphql/persisted-queries/{bff}.json`（当前只有 customer）。
 * 格式：`{ "q_ai_findMyScanById": "query q_ai_findMyScanById($id: UUID!) { ... }" }`
 *       （key = reqName，value = 完整 query 文本；由前端 build 时提取生成并提交进本 repo）
 *
 * 启动时解析每条 query 为 graphql Document 缓存。解析失败的条目跳过并告警（不阻断启动）。
 */
@Component
class ClasspathPersistedQueryStore(
    @param:Value("\${graphql.trusted-documents.allowlist-path:classpath:graphql/persisted-queries/}")
    private val allowlistPath: String,
    private val mapper: ObjectMapper,
) : PersistedQueryStore {

    private val log = LoggerFactory.getLogger(ClasspathPersistedQueryStore::class.java)
    private val parser = Parser()

    /** bff -> (reqName -> entry) */
    private val storesByBff = mutableMapOf<String, Map<String, PersistedQueryEntry>>()

    @PostConstruct
    fun init() {
        val resolver = PathMatchingResourcePatternResolver()
        for (bff in BFFS) {
            val resource = resolver.getResource("$allowlistPath$bff.json")
            if (!resource.exists()) {
                storesByBff[bff] = emptyMap()
                log.info("trusted-documents: no allowlist. bff={} path={}{}", bff, allowlistPath, "$bff.json")
                continue
            }
            val entries = mutableMapOf<String, PersistedQueryEntry>()
            resource.inputStream.use { stream ->
                val root = mapper.readTree(stream)
                root.properties().forEach { (reqName, node) ->
                    val query = node.asString()
                    val doc = try {
                        parser.parseDocument(query)
                    } catch (e: Exception) {
                        log.warn("trusted-documents: skip unparsable query. reqName={} bff={} error={}", reqName, bff, e.message)
                        return@forEach
                    }
                    entries[reqName] = PersistedQueryEntry(reqName = reqName, query = query, document = doc)
                }
            }
            storesByBff[bff] = entries
            log.info("trusted-documents: loaded persisted queries. count={} bff={}", entries.size, bff)
        }
    }

    override fun getByReqName(reqName: String, bff: String): PersistedQueryEntry? =
        storesByBff[bff]?.get(reqName)

    companion object {
        /** 当前只有 customer BFF；未来加 admin/manager 时在此登记。 */
        private val BFFS = listOf("customer")
    }
}
