package com.ifmix.core.api.bff.api.customer.media

import com.ifmix.core.api.bff.api.customer.demo.DemoController
import com.ifmix.core.api.dto.storage.PresignDownloadInput
import com.ifmix.core.api.dto.storage.PresignDownloadResult
import com.ifmix.core.api.dto.storage.PresignUploadInput
import com.ifmix.core.api.dto.storage.PresignUploadResult
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.modules.media.StorageFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * media 模块 API controller：2 个 `POST /api/customer/core/{actionName}`（rollout-server §4 M1）。
 *
 * 模式与 [DemoController] 完全一致：[ActionContextFactory.fromRpc] 构造 ctx → 直调 [StorageFacade]。
 * 原 StorageFetcher 两个 action 均未包 GlobalTxRunner（presign 无聚合事务，upload record 单条写入在
 * Handler 内）——此处同样不包，事务边界对齐。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Media API", description = "media 模块 API（GraphQL 去化 M1）")
class MediaController(
    private val ctxFactory: ActionContextFactory,
    private val facade: StorageFacade,
) {

    companion object {
        // media 模块 action 常量（原 MediaSpecs 机械搬移；CUSTOMER + requireProjectId=true，
        // 对照原 StorageFetcher `fromDfe(dfe)` 全默认实参）。
        const val PRESIGN_UPLOAD = "m_media_media_presignUpload"
        const val PRESIGN_DOWNLOAD = "m_media_media_presignDownload"
    }

    @Operation(operationId = PRESIGN_UPLOAD)
    @PostMapping(PRESIGN_UPLOAD, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun presignUpload(request: HttpServletRequest, @RequestBody body: ApiRequestBody<PresignUploadInput>): ResponseEntity<Envelope<PresignUploadResult>> {
        val ctx = ctxFactory.fromRpc(request, PRESIGN_UPLOAD, isMutation = true, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, facade.presignUpload(ctx, input)))
    }

    @Operation(operationId = PRESIGN_DOWNLOAD)
    @PostMapping(PRESIGN_DOWNLOAD, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun presignDownload(request: HttpServletRequest, @RequestBody body: ApiRequestBody<PresignDownloadInput>): ResponseEntity<Envelope<PresignDownloadResult>> {
        val ctx = ctxFactory.fromRpc(request, PRESIGN_DOWNLOAD, isMutation = true, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, facade.presignDownload(ctx, input)))
    }
}
