package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.AntiqueService
import com.ifmix.api.core.service.antique.CreateScanRequest
import com.ifmix.api.core.service.antique.NewScanReq
import com.ifmix.api.core.service.antique.NewScanRes
import com.ifmix.api.core.service.antique.ScanDto
import io.swagger.v3.oas.annotations.Operation
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
@RequestMapping("/customer/core", produces = ["application/json"])
@ConditionalOnBean(AntiqueService::class)
class CustomerAntiqueController(private val antiqueService: AntiqueService) {

    @Operation(
        summary = "扫描古物（同步）",
        description = """
            接受已上传图片的 imageKey，同步调用 AI 模型识别，阻塞直到分析完成才返回（典型 3-8秒）。
            返回的 result 必定是 COMPLETED 状态的完整结果，无需轮询。
            失败场景走 ErrorEnvelope（503000 AI_UNAVAILABLE 或 429000 RATE_LIMITED）。
            
            id 关系说明：
            - NewScanRes.id = ScanDto.id = 数据库主键（UUIDv7）
            - 所有需要传 scanRecordId 的地方（收藏/反馈/getById）都用这个 id
            - ScanDto.scanId 是历史兼容字段，前端不需要使用
        """,
    )
    @PostMapping("/mutation/antique/newScan")
    fun newScan(ctx: RequestContext, @Valid @RequestBody req: NewScanReq): NewScanRes {
        return antiqueService.newScan(ctx, req)
    }

    /** 创建扫描任务（脚手架 CRUD，保留兼容）。 */
    @io.swagger.v3.oas.annotations.Hidden
    @PostMapping("/mutation/antique/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateScanRequest): ScanDto {
        val record = antiqueService.createScan(ctx, req)
        return with(antiqueService) { record.toDto() }
    }

    @Operation(
        summary = "按 ID 获取扫描记录",
        description = """
            id 为 UUIDv7 格式的扫描记录主键（即 NewScanRes.id / ScanDto.id）。
            按当前用户过滤，他人的 id 返回 404。
            ScanDto.result 可能为 null（仅当记录通过内部 createOne 创建但未触发 AI 时）。
            imageUrl 是预签名 URL，有效期约 1 小时，过期后用 imageKey 调 storage/presignDownload 续签。
        """,
    )
    @PutMapping("/query/antique/getById")
    fun getById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): ScanDto {
        return antiqueService.getScanResult(ctx, req.id.toString())
    }

    @Operation(
        summary = "游标分页查询扫描记录",
        description = """
            按当前用户过滤，只返回自己的记录。
            默认按 createdAt DESC 排序，limit 默认 20，上限 100。
            body 完全可选，不传等同默认参数。
        """,
    )
    @PutMapping("/query/antique/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<ScanDto> {
        val page = antiqueService.findByCursor(ctx, input ?: CursorQueryInput())
        val dtos = page.items.map { record ->
            with(antiqueService) { record.toDto() }
        }
        return Page(dtos, page.nextCursor, page.hasMore)
    }

    data class ByIdRequest(val id: java.util.UUID)
}
