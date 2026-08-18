package com.ifmix.api.core.bff.graphql.customer.storage

import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignDownloadPayload
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.generated.types.PresignUploadPayload
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.storage.UploadRecord
import com.ifmix.api.core.modules.scan.service.AntiqueService
import com.ifmix.api.core.modules.storage.repo.UploadRecordRepository
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.time.Duration
import java.time.Instant

@DgsComponent
class StorageFetcher(
    private val antiqueService: AntiqueService,
    private val uploadRecordRepo: UploadRecordRepository,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "mutation_storage_presignUpload")
    fun presignUpload(dfe: DgsDataFetchingEnvironment, @InputArgument input: PresignUploadInput): PresignUploadPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val appId = ctx.appId!!
        val installId = ctx.installId!!
        val mediaId = UuidV7.generate()

        val category = "antique_scan"
        val ext = when (input.contentType) {
            100 -> "jpg"
            200 -> "png"
            300 -> "webp"
            else -> throw IllegalArgumentException("unsupported contentType: $input.contentType")
        }
        val mimeType = when (input.contentType) {
            100 -> "image/jpeg"
            200 -> "image/png"
            300 -> "image/webp"
            else -> throw IllegalArgumentException("unsupported contentType: $input.contentType")
        }

        val objectKey = "app/$appId/$category/install/$installId/$mediaId.$ext"
        val url = antiqueService.presignedUploadUrl(ctx, objectKey, mimeType, Duration.ofSeconds(300))
        val downloadUrl = antiqueService.getPublicUrl(ctx, objectKey)

        uploadRecordRepo.insert(SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl), UploadRecord(
            id = mediaId,
            appId = appId,
            installId = installId,
            userId = ctx.userId,
            objectKey = objectKey,
            contentType = mimeType,
            category = category,
            clientIp = ctx.clientIp,
            createdAt = Instant.now(),
        ))

        return PresignUploadPayload(
            mediaId = mediaId,
            uploadUrl = url,
            imageKey = objectKey,
            downloadUrl = downloadUrl,
        )
    }

    @DgsMutation(field = "mutation_storage_presignDownload")
    fun presignDownload(dfe: DgsDataFetchingEnvironment, @InputArgument input: PresignDownloadInput): PresignDownloadPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val duration = Duration.ofSeconds((input.durationSeconds ?: 3600).toLong())
        val url = antiqueService.presignedDownloadUrl(ctx, input.imageKey, duration)
        return PresignDownloadPayload(url = url)
    }
}
