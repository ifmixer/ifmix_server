package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.AntiqueService
import com.ifmix.api.core.service.antique.CreateScanRequest
import com.ifmix.api.core.service.antique.ScanDto
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * customer BFF 的古物扫描路由。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(AntiqueService::class)
class CustomerAntiqueController(private val antiqueService: AntiqueService) {

    /** 创建扫描任务。 */
    @PostMapping("/mutation/antique/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateScanRequest): ScanDto {
        val record = antiqueService.createScan(ctx, req)
        return ScanDto(
            id = record.id.toString(),
            scanId = record.scanId,
            imageUrl = record.imageUrl,
            status = record.status,
            resultJson = record.resultJson,
            tier = record.tier,
            clientIp = record.clientIp,
            relatedId = record.relatedId,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }

    /** 按 ID 获取扫描记录详情。 */
    @PutMapping("/query/antique/getById")
    fun getById(ctx: RequestContext, @RequestBody req: ByIdRequest): ScanDto {
        val id = req.id ?: throw ApiError(ErrorCode.INVALID_REQUEST, "id is required")
        return antiqueService.getScanResult(ctx, id)
    }

    /** 游标分页查询扫描记录列表。 */
    @PutMapping("/query/antique/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<ScanDto> {
        val page = antiqueService.findByCursor(ctx, input ?: CursorQueryInput())
        val dtos = page.items.map { record ->
            ScanDto(
                id = record.id.toString(),
                scanId = record.scanId,
                imageUrl = record.imageUrl,
                status = record.status,
                resultJson = record.resultJson,
                tier = record.tier,
                clientIp = record.clientIp,
                relatedId = record.relatedId,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            )
        }
        return Page(dtos, page.nextCursor, page.hasMore)
    }

    data class ByIdRequest(val id: String?)
}
