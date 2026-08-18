package com.ifmix.api.core.bff.graphql.customer.scan

import com.ifmix.api.core.generated.types.DeleteScanPayload
import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.NewScanPayload
import com.ifmix.api.core.generated.types.ScanQueryInput
import com.ifmix.api.core.generated.types.ScanRecordPage
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.UpdateScanPayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.entity.scan.ScanRecord
import com.ifmix.api.core.modules.ai.service.AiFacadeService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

@DgsComponent
class ScanFetcher(
    private val scanService: AiFacadeService,
    private val ctxProvider: OperationContextProvider,
) {
    @DgsQuery(field = "query_scan_findScanById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): ScanRecord {
        val ctx = ctxProvider.fromDfe(dfe)
        return scanService.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "query_scan_findScansByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ScanQueryInput?): ScanRecordPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val q = input ?: ScanQueryInput()
        val page = scanService.findByCursorFiltered(ctx, q.cursor, q.limit, q.collected)
        return ScanRecordPage(items = page.items, nextCursor = page.nextCursor, hasMore = page.hasMore)
    }

    @DgsMutation(field = "mutation_scan_createScan")
    fun newScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: NewScanInput): NewScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return NewScanPayload(scanRecord = scanService.newScan(ctx, input))
    }

    @DgsMutation(field = "mutation_scan_updateScan")
    fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = scanService.updateScan(ctx, input)
        val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
            scanService.findById(ctx, input.id)
        } else null
        return UpdateScanPayload(success = success, scanRecord = record)
    }

    @DgsMutation(field = "mutation_scan_deleteScan")
    fun deleteScanById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = scanService.deleteScan(ctx, id)
        return DeleteScanPayload(success = success)
    }
}
