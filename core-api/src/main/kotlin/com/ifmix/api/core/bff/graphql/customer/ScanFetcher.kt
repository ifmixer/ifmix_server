package com.ifmix.api.core.bff.graphql.customer

import com.ifmix.api.core.generated.types.DeleteScanPayload
import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.NewScanPayload
import com.ifmix.api.core.generated.types.ScanQueryInput
import com.ifmix.api.core.generated.types.ScanRecord as DgsScanRecord
import com.ifmix.api.core.generated.types.ScanRecordPage
import com.ifmix.api.core.generated.types.ScanStatus as DgsScanStatus
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.UpdateScanPayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.generated.types.ImageRef
import com.ifmix.api.core.model.ScanRecord
import com.ifmix.api.core.modules.scan.service.AntiqueService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

@DgsComponent
class ScanFetcher(
    private val scanService: AntiqueService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsQuery(field = "query_findScanById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DgsScanRecord {
        val ctx = ctxProvider.fromDfe(dfe)
        val model = scanService.findById(ctx, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND)
        return toDgs(model)
    }

    @DgsQuery(field = "query_findScansByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ScanQueryInput?): ScanRecordPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val q = input ?: ScanQueryInput()
        val page = scanService.findByCursorFiltered(ctx, q.cursor, q.limit, q.collected)
        return ScanRecordPage(
            items = page.items.map { toDgs(it) },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation(field = "mutation_newScan")
    fun newScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: NewScanInput): NewScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val model = scanService.newScan(ctx, input)
        return NewScanPayload(scanRecord = toDgs(model))
    }

    @DgsMutation(field = "mutation_updateScan")
    fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = scanService.updateScan(ctx, input)
        val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
            scanService.findById(ctx, input.id)?.let { toDgs(it) }
        } else null
        return UpdateScanPayload(success = success, scanRecord = record)
    }

    @DgsMutation(field = "mutation_deleteScanById")
    fun deleteScanById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = scanService.deleteScan(ctx, id)
        return DeleteScanPayload(success = success)
    }

    private fun toDgs(m: ScanRecord): DgsScanRecord = DgsScanRecord(
        id = m.id,
        images = m.images.map { ImageRef(key = it.key) },
        result = m.result,
        status = shortToScanStatus(m.status),
        clientIp = m.clientIp,
        lang = m.lang,
        country = m.country,
        currency = m.currency,
        userDisplayName = m.userDisplayName,
        userNotes = m.userNotes,
        collected = m.collected,
        createdAt = m.createdAt,
        updatedAt = m.updatedAt,
    )

    private fun shortToScanStatus(code: Short): DgsScanStatus = when (code.toInt()) {
        100 -> DgsScanStatus.PENDING
        110 -> DgsScanStatus.PROCESSING
        200 -> DgsScanStatus.COMPLETED
        300 -> DgsScanStatus.FAILED
        else -> DgsScanStatus.PENDING
    }
}
