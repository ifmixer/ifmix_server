package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.antique.AntiqueService
import com.ifmix.api.core.modules.antique.CreateScanRequest
import com.ifmix.api.core.modules.antique.ScanDto
import com.ifmix.api.core.modules.antique.ScanListItemDto
import com.ifmix.api.core.modules.antique.ScanMapper
import io.mcarle.konvert.api.Konverter
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*

/**
 * customer BFF 的古物扫描路由。
 * 仅在 AntiqueService bean 存在时加载（即 app.storage.type=s3 时）。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(com.ifmix.api.core.modules.antique.AntiqueService::class)
class CustomerAntiqueController(private val antiqueService: com.ifmix.api.core.modules.antique.AntiqueService) {

    private val mapper: ScanMapper = Konverter.get()

    /** 创建扫描任务。 */
    @PostMapping("/mutation/antique/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateScanRequest): ScanDto {
        val id = antiqueService.createScan(ctx, req)
        return mapper.toDto(antiqueService.getScanRecordById(id))
    }

    /** 按 ID 获取扫描记录详情。 */
    @PutMapping("/query/antique/getById")
    fun getById(ctx: RequestContext, @RequestBody req: ByIdRequest): ScanDto {
        return mapper.toDto(antiqueService.getScanRecordById(req.id!!))
    }

    /** 游标分页查询扫描记录列表。 */
    @PutMapping("/query/antique/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<ScanListItemDto> {
        val page = antiqueService.findByCursor(ctx, input ?: CursorQueryInput())
        return Page(page.items.map { mapper.toListItemDto(it) }, page.nextCursor, page.hasMore)
    }

    /** 按 ID 请求体（复用 todo 模块的 by-id 格式）。 */
    data class ByIdRequest(val id: String?)
}
