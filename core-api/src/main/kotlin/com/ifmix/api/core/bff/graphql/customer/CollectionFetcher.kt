package com.ifmix.api.core.bff.graphql.customer

import com.ifmix.api.core.entity.scan.ImageRef
import com.ifmix.api.core.generated.types.AddScanCollectionItemInput
import com.ifmix.api.core.generated.types.AddScanCollectionItemPayload
import com.ifmix.api.core.generated.types.ListScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsInput
import com.ifmix.api.core.generated.types.RemoveScanCollectionItemsPayload
import com.ifmix.api.core.generated.types.ScanCollection as DgsScanCollection
import com.ifmix.api.core.generated.types.ScanCollectionItem as DgsScanCollectionItem
import com.ifmix.api.core.generated.types.ScanCollectionItemPage
import com.ifmix.api.core.generated.types.ScanRecord as DgsScanRecord
import com.ifmix.api.core.generated.types.ScanStatus
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.model.ScanCollection
import com.ifmix.api.core.model.ScanCollectionItem
import com.ifmix.api.core.model.ScanRecord
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import com.ifmix.api.core.modules.scan.service.ScanCollectionService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import org.dataloader.MappedBatchLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@DgsComponent
class CollectionFetcher(
    private val collectionService: ScanCollectionService,
    private val scanRecordRepo: ScanRecordRepository,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsQuery(field = "query_getDefaultScanCollection")
    fun getDefault(dfe: DgsDataFetchingEnvironment): DgsScanCollection {
        val ctx = ctxProvider.fromDfe(dfe)
        return toDgsCollection(collectionService.getDefault(ctx))
    }

    @DgsQuery(field = "query_findScanCollectionItemsByCursor")
    fun findItemsByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: ListScanCollectionItemsInput?): ScanCollectionItemPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val page = collectionService.findItemsByCursor(ctx, input)
        return ScanCollectionItemPage(
            items = page.items.map { toItem(it) },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsData(parentType = "ScanCollectionItem", field = "scanRecord")
    fun scanRecord(dfe: DgsDataFetchingEnvironment): CompletableFuture<DgsScanRecord> {
        val item: ScanCollectionItem = dfe.getSource()!!
        val loader = dfe.getDataLoader<UUID, List<ScanRecord>>(ScanRecordsDataLoader.NAME)!!
        return loader.load(item.scanRecordId).thenApply { records ->
            records.firstOrNull()?.let { toDgsScanRecord(it) }
                ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found: ${item.scanRecordId}")
        }
    }

    @DgsMutation(field = "mutation_addScanCollectionItem")
    fun addItem(dfe: DgsDataFetchingEnvironment, @InputArgument input: AddScanCollectionItemInput): AddScanCollectionItemPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return collectionService.addItem(ctx, input)
    }

    @DgsMutation(field = "mutation_removeScanCollectionItems")
    fun removeItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: RemoveScanCollectionItemsInput): RemoveScanCollectionItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        return collectionService.removeItems(ctx, input)
    }

    private fun toDgsCollection(c: ScanCollection): DgsScanCollection = DgsScanCollection(
        id = c.id, isDefault = c.isDefault, createdAt = c.createdAt,
    )

    private fun toItem(item: ScanCollectionItem): DgsScanCollectionItem = DgsScanCollectionItem(
        id = item.id,
        scanRecord = null as DgsScanRecord,
        createdAt = item.createdAt,
    )

    private fun toDgsScanRecord(r: ScanRecord): DgsScanRecord = DgsScanRecord(
        id = r.id,
        images = r.images.map { ImageRef(key = it.key) },
        result = r.result,
        status = when (r.status) {
            100.toShort() -> ScanStatus.PENDING
            110.toShort() -> ScanStatus.PROCESSING
            200.toShort() -> ScanStatus.COMPLETED
            300.toShort() -> ScanStatus.FAILED
            else -> ScanStatus.PENDING
        },
        clientIp = r.clientIp, lang = r.lang, country = r.country, currency = r.currency,
        userDisplayName = r.userDisplayName, userNotes = r.userNotes,
        collected = r.collected, createdAt = r.createdAt, updatedAt = r.updatedAt,
    )
}

@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(private val scanRecordRepo: ScanRecordRepository) : MappedBatchLoader<UUID, List<ScanRecord>> {
    override fun load(scanRecordIds: Set<UUID>): CompletionStage<Map<UUID, List<ScanRecord>>> {
        val records = scanRecordRepo.findByIds(RepoContext.DEFAULT, scanRecordIds)
        val grouped = records.groupBy { it.id }
        val result = scanRecordIds.associateWith { grouped[it] ?: emptyList() }
        return CompletableFuture.completedFuture(result)
    }
    companion object { const val NAME = "scanRecords" }
}
