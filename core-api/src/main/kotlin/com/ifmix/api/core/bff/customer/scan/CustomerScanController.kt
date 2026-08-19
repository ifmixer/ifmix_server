package com.ifmix.api.core.bff.customer.scan

import com.ifmix.api.core.infra.dto.ByIdRequest
import com.ifmix.api.core.infra.dto.OperationResult
import com.ifmix.api.core.entity.scan.dto.ScanRecordDto
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.scan.ScanFacade
import com.ifmix.api.core.modules.scan.dto.NewScanReq
import com.ifmix.api.core.modules.scan.dto.ScanQueryInput
import com.ifmix.api.core.modules.scan.dto.UpdateScanReq
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
@RequestMapping("/customer")
@ConditionalOnBean(ScanFacade::class)
class CustomerScanController(private val scanFacade: ScanFacade) {

    @Operation(
        summary = "扫描古物（同步）",
        description = """
            接受已上传图片的 imageKey，同步调用 AI 模型识别，阻塞直到分析完成才返回（典型 3-8秒）。
            返回的 result 必定是 COMPLETED 状态的完整结果，无需轮询。
            失败场景走 ErrorEnvelope（503000 AI_UNAVAILABLE 或 429000 RATE_LIMITED）。

            id 关系说明：
            - NewScanRes.id = ScanRecordDto.id = 数据库主键（UUIDv7）
            - 所有需要传 scanRecordId 的地方（收藏/反馈/findById）都用这个 id
        """,
    )
    @PostMapping("/mutation/core/scan/newScan")
    fun newScan(ctx: OperationContext, @Valid @RequestBody req: NewScanReq): ScanRecordDto {
        val record = scanFacade.newScan(ctx, req)
        return ScanRecordDto(record)
    }

    @Operation(
        summary = "按 ID 获取扫描记录",
        description = """
            id 为 UUIDv7 格式的扫描记录主键（即 NewScanRes.id）。
            按当前用户过滤，他人的 id 返回 404。已软删的记录也返回 404。
            result 可能为 null（仅当记录通过内部 createOne 创建但未触发 AI 时）。
            图片通过 imageKeys 中的 objectKey 调 storage/presignDownload 获取临时 URL。
        """,
    )
    @PutMapping("/query/core/scan/findScanById")
    fun findById(ctx: OperationContext, @Valid @RequestBody req: ByIdRequest): ScanRecordDto {
        return scanFacade.getScanById(ctx, req.id)
    }

    @Operation(
        summary = "游标分页查询扫描记录",
        description = """
            按当前用户过滤，只返回自己的记录。已软删记录不会出现。
            默认按 createdAt DESC 排序，limit 默认 20，上限 100。
            body 完全可选，不传等同默认参数。
        """,
    )
    @PutMapping("/query/core/scan/findScansByCursor")
    fun findByCursor(
        ctx: OperationContext,
        @RequestBody(required = false) input: ScanQueryInput?,
    ): Page<ScanRecordDto> {
        val page = scanFacade.findByCursor(ctx, input ?: ScanQueryInput())
        val views = page.items.map { ScanRecordDto(it) }
        return Page(views, page.nextCursor, page.hasMore)
    }

    @Operation(
        summary = "删除扫描记录",
        description = """
            软删除（标记 deletedAt），不可恢复。同时自动从所有收藏夹中移除。
            按当前用户过滤，他人的 id 返回 404。
            已软删的记录不会出现在 findByCursor / listItems 列表中；findById 也返回 404。
            删除不退还扫描配额（日配额为消耗计数，删除记录不影响已用额度）。
        """,
    )
    @PostMapping("/mutation/core/scan/deleteScanById")
    fun deleteById(ctx: OperationContext, @Valid @RequestBody req: ByIdRequest): OperationResult {
        scanFacade.deleteScan(ctx, req.id)
        return OperationResult()
    }

    @Operation(
        summary = "更新扫描记录",
        description = """
            当前支持修改 name 和 userNotes。其他字段不可编辑。
            按当前用户过滤，他人的 id 返回 404。
            name: 不传=不修改；传 null=清空（回退到 AI result.name 快照）；传字符串=用户改名。
            userNotes: 不传=不修改；传 null=清空；传字符串=设置用户备注。
        """,
    )
    @PostMapping("/mutation/core/scan/updateScan")
    fun updateOne(ctx: OperationContext, @Valid @RequestBody req: UpdateScanReq): OperationResult {
        scanFacade.updateScan(ctx, req)
        return OperationResult(success = true, modifiedCount = 1)
    }
}
