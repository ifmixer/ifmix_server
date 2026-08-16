package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.infra.http.OperationContext
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.ifmix.api.core.modules.scan.service.AntiqueService
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Customer GraphQL fetcher for scan records.
 *
 * 注意：当前返回基础 placeholder 数据。
 * Phase 3 迁移到 Exposed 后，将接入真实的 ScanService 查询。
 */
@DgsComponent
class CustomerScanFetcher(
    private val scanService: AntiqueService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 根据 ID 获取扫描记录。
     * id 参数为 Base58 编码的 UUID 字符串（实际当前直接按 UUID 解析）。
     */
    @DgsQuery(field = "scan_get")
    fun getById(@InputArgument id: String): Any? {
        return try {
            val uuid = UUID.fromString(id)
            val ctx = OperationContext()
            // TODO: Phase 3 — 从 DB 查询完整记录并映射到 GraphQL type
            log.debug("scan_get called with id={}, appId={}", uuid, ctx.appId)
            mapOf(
                "id" to id,
                "status" to "UNKNOWN",
                "appId" to id,
            )
        } catch (e: Exception) {
            log.warn("scan_get invalid id: {}", id, e)
            null
        }
    }
}
