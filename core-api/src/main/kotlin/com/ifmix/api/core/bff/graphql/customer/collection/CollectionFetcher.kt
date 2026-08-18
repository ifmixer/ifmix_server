package com.ifmix.api.core.bff.graphql.customer.collection

import com.ifmix.api.core.generated.types.AddScanCollectionItemInput
import com.ifmix.api.core.generated.types.AddScanCollectionItemPayload
import com.ifmix.api.core.generated.types.ListScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsPayload
import com.ifmix.api.core.generated.types.ScanCollectionItem as DgsScanCollectionItem
import com.ifmix.api.core.generated.types.ScanCollectionItemPage
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.model.scan.ScanCollectionItem
import com.ifmix.api.core.model.scan.ScanRecord
import com.ifmix.api.core.dto.scan.AddItemReq
import com.ifmix.api.core.dto.scan.ListItemsReq
import com.ifmix.api.core.dto.scan.RemoveItemsReq
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import com.ifmix.api.core.modules.scan.service.ScanCollectionFacadeService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import org.dataloader.MappedBatchLoader
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@DgsComponent
class CollectionFetcher(
    private val collectionService: ScanCollectionFacadeService,
    private val scanRecordRepo: ScanRecordRepository,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsQuery(field = "query_collection_getDefaultCollection")
    fun getDefault(dfe: DgsDataFetchingEnvironment): com.ifmix.api.core.model.scan.ScanCollection {
        val ctx = ctxProvider.fromDfe(dfe)
        return collectionService.getDefault(ctx)
    }

    @DgsQuery(field = "query_collection_findCollectionItemsByCursor")
    fun findItemsByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ListScanCollectionItemsInput?): ScanCollectionItemPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val req = input?.let { ListItemsReq(cursor = it.cursor, limit = it.limit, collectionId = null) }
        val page = collectionService.findItemsByCursor(ctx, req)
        return ScanCollectionItemPage(
            items = page.items.map { item ->
                DgsScanCollectionItem(
                    id = item.id,
                    scanRecord = ScanRecord(
                        id = item.scanRecordId, appId = ctx.appId!!,
                        imageKeys = emptyList(), result = null,
                        status = 0, collected = false, createdAt = Instant.EPOCH, updatedAt = null, deletedAt = null
                    ),
                    createdAt = item.createdAt,
                )
            },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsData(parentType = "ScanCollectionItem", field = "scanRecord")
    fun scanRecord(dfe: DgsDataFetchingEnvironment): CompletableFuture<ScanRecord> {
        throw ApiError(ErrorCode.NOT_FOUND, "not implemented")
    }

    @DgsMutation(field = "mutation_collection_addCollectionItem")
    fun addItem(dfe: DgsDataFetchingEnvironment, @InputArgument input: AddScanCollectionItemInput): AddScanCollectionItemPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val restResult = collectionService.addItem(ctx, AddItemReq(scanRecordId = input.scanRecordId))
        return AddScanCollectionItemPayload(collectionId = restResult.id, alreadyExists = false)
    }

    @DgsMutation(field = "mutation_collection_removeCollectionItems")
    fun removeItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: RemoveScanCollectionItemsInput): RemoveScanCollectionItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val restResult = collectionService.removeItems(ctx, RemoveItemsReq(scanRecordIds = input.scanRecordIds))
        return RemoveScanCollectionItemsPayload(removedCount = restResult.removed)
    }
}

@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(private val scanRecordRepo: ScanRecordRepository) : MappedBatchLoader<UUID, List<ScanRecord>> {
    override fun load(scanRecordIds: Set<UUID>): CompletionStage<Map<UUID, List<ScanRecord>>> {
        val records = scanRecordRepo.findByIds(SvcCtx.DEFAULT, scanRecordIds)
        val grouped = records.groupBy { it.id }
        val result = scanRecordIds.associateWith { grouped[it] ?: emptyList() }
        return CompletableFuture.completedFuture(result)
    }
    companion object { const val NAME = "scanRecords" }
}
