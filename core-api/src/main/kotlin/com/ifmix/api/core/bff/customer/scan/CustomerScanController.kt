package com.ifmix.api.core.bff.customer.scan

import com.ifmix.api.core.infra.dto.OperationResult
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.scan.dto.NewScanReq
import com.ifmix.api.core.modules.scan.dto.ScanQueryInput
import com.ifmix.api.core.modules.scan.dto.UpdateScanReq
import com.ifmix.api.core.modules.scan.service.AntiqueService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class ScanRecordRestDto(
    val id: UUID,
    val images: List<com.ifmix.api.core.entity.scan.ImageRef>,
    val result: Any? = null,
    val status: Int,
    val clientIp: String? = null,
    val lang: String? = null,
    val country: String? = null,
    val currency: String? = null,
    val userDisplayName: String? = null,
    val userNotes: String? = null,
    val collected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
) {
    companion object {
        fun fromModel(m: com.ifmix.api.core.model.ScanRecord) = ScanRecordRestDto(
            id = m.id, images = m.images, result = m.result,
            status = m.status.toInt(), clientIp = m.clientIp, lang = m.lang,
            country = m.country, currency = m.currency,
            userDisplayName = m.userDisplayName, userNotes = m.userNotes,
            collected = m.collected, createdAt = m.createdAt, updatedAt = m.updatedAt,
        )
    }
}

@RestController
@RequestMapping("/customer")
@ConditionalOnBean(AntiqueService::class)
class CustomerScanController(private val antiqueService: AntiqueService) {

    @Operation(summary = "扫描古物（同步）")
    @PostMapping("/mutation/core/scan/newScan")
    fun newScan(ctx: OperationContext, @Valid @RequestBody req: NewScanReq): ScanRecordRestDto {
        val input = com.ifmix.api.core.generated.types.NewScanInput(
            images = req.images.map { com.ifmix.api.core.generated.types.NewScanImageInput(it.imageKey, it.mediaType) }
        )
        return ScanRecordRestDto.fromModel(antiqueService.newScan(ctx, input))
    }

    @Operation(summary = "按 ID 获取扫描记录")
    @PutMapping("/query/core/scan/findScanById")
    fun findById(ctx: OperationContext, @RequestBody req: com.ifmix.api.core.infra.dto.ByIdRequest): ScanRecordRestDto {
        val record = antiqueService.findById(ctx, req.id)
            ?: throw com.ifmix.api.core.infra.http.ApiError(com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND)
        return ScanRecordRestDto.fromModel(record)
    }

    @Operation(summary = "游标分页查询扫描记录")
    @PutMapping("/query/core/scan/findScansByCursor")
    fun findByCursor(
        ctx: OperationContext,
        @RequestBody(required = false) input: ScanQueryInput?,
    ): Page<ScanRecordRestDto> {
        val q = input ?: ScanQueryInput()
        val page = antiqueService.findByCursorFiltered(ctx, q.cursor, q.limit, q.collected)
        return Page(page.items.map { ScanRecordRestDto.fromModel(it) }, page.nextCursor, page.hasMore)
    }

    @Operation(summary = "删除扫描记录")
    @PostMapping("/mutation/core/scan/deleteScanById")
    fun deleteById(ctx: OperationContext, @RequestBody req: com.ifmix.api.core.infra.dto.ByIdRequest): OperationResult {
        antiqueService.deleteScan(ctx, req.id)
        return OperationResult()
    }

    @Operation(summary = "更新扫描记录")
    @PostMapping("/mutation/core/scan/updateScan")
    fun updateOne(ctx: OperationContext, @Valid @RequestBody req: UpdateScanReq): OperationResult {
        val input = com.ifmix.api.core.generated.types.UpdateScanInput(
            id = req.id,
            set = com.ifmix.api.core.generated.types.UpdateScanSetInput(
                userDisplayName = req.name,
                userNotes = req.userNotes,
                collected = req.collected,
            )
        )
        antiqueService.updateScan(ctx, input)
        return OperationResult(success = true, modifiedCount = 1)
    }
}
