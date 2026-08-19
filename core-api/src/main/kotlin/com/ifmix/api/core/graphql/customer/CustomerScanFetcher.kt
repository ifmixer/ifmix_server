package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.graphql.generated.types.ScanConnection
import com.ifmix.api.core.graphql.generated.types.ScanRecord
import com.ifmix.api.core.modules.antique.AntiqueService
import com.ifmix.api.core.modules.antique.CreateScanRequest
import com.ifmix.api.core.modules.antique.toScanRecord
import com.ifmix.api.core.modules.antique.repo.ScanRecordRepository
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

/** Map field from GraphQL input to ScanRecordEntity patch. */
private fun mapCollectedPatch(collected: Boolean): Map<String, Any?> =
    mapOf("collected" to collected, "updatedAt" to java.time.Instant.now())

@DgsComponent
class CustomerScanFetcher(
    private val antiqueService: AntiqueService,
    private val scanRecordRepo: ScanRecordRepository,
) {

    @DgsQuery(field = "scan_get")
    fun scanRecord(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): ScanRecord? {
        val ctx = getContext(dfe)
        val doc = antiqueService.getScanRecordById(id)
        return doc.toScanRecord()
    }

    @DgsQuery(field = "scan_list")
    fun scanRecords(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument collected: Boolean?,
        dfe: DgsDataFetchingEnvironment,
    ): ScanConnection {
        val ctx = getContext(dfe)
        val input = CursorQueryInput(cursor = cursor, limit = limit)
        val page = antiqueService.findByCursor(ctx, input)
        return ScanConnection(
            items = page.items.map { it.toScanRecord() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation(field = "scan_create")
    fun newScan(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): ScanRecord {
        val ctx = getContext(dfe)
        val imageUrl = input["imageUrl"] as String
        val relatedId = input["relatedId"] as? String
        val request = CreateScanRequest(imageUrl = imageUrl, relatedId = relatedId)
        val id = antiqueService.createScan(ctx, request)
        return antiqueService.getScanRecordById(id).toScanRecord()
    }

    @DgsMutation(field = "scan_update")
    fun updateScan(
        @InputArgument id: String,
        @InputArgument collected: Boolean,
        dfe: DgsDataFetchingEnvironment,
    ): ScanRecord {
        val ctx = getContext(dfe)
        scanRecordRepo.updateById(ctx, id, mapCollectedPatch(collected))
        return antiqueService.getScanRecordById(id).toScanRecord()
    }

    @DgsMutation(field = "scan_delete")
    fun deleteScan(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return scanRecordRepo.deleteById(ctx, id)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): RequestContext =
        DgsContext.getCustomContext<RequestContext>(dfe)
}
