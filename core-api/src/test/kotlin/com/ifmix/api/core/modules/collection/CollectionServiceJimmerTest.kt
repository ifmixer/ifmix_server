package com.ifmix.api.core.modules.collection

import com.ifmix.api.core.common.http.RequestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CollectionServiceJimmerTest {

    private lateinit var service: CollectionService
    private val collectionRepo = mock<CollectionRepository>()
    private val itemRepo = mock<CollectionItemRepository>()

    private val ctx = RequestContext(appId = "app-1", userId = "user-1", installId = "install-1")

    @BeforeEach
    fun init() {
        service = CollectionService(collectionRepo, itemRepo)
    }

    @Test
    fun `getDefault - should create default collection if none exists`() {
        // Arrange
        whenever(collectionRepo.findDefault(ctx)).thenReturn(null)
        val mockCollection = com.ifmix.api.core.common.jimmer.entity.collection.Collection {
            id = java.util.UUID.randomUUID()
            appId = java.util.UUID.fromString("app-1")
            installId = "install-1"
            userId = "user-1"
            isDefault = true
            createdAt = java.time.Instant.now()
            updatedAt = java.time.Instant.now()
            items = emptyList()
            deletedAt = null
        }
        whenever(collectionRepo.setup { collectionSetup -> collectionSetup.appId eq ctx.appId })

        // Act
        val result = service.getDefault(ctx)

        // Assert
        assertThat(result.isDefault).isTrue()
    }

    @Test
    fun `addItem - should add item to collection`() {
        // Arrange
        val collectionId = "test-collection-id"
        whenever(service.resolveCollectionId(ctx, null)).thenReturn(collectionId)
        whenever(itemRepo.insertIfAbsent(ctx, collectionId, "scan-123")).thenReturn("item-id-1")

        // Act
        val req = Collection.AddItemReq(collectionId = null, scanRecordId = "scan-123")
        val result = service.addItem(ctx, req)

        // Assert
        assertThat(result).isEqualTo("item-id-1")
    }

    @Test
    fun `removeItems - should remove items from collection`() {
        // Arrange
        val collectionId = "test-collection-id"
        whenever(service.resolveCollectionId(ctx, "coll-id")).thenReturn(collectionId)
        whenever(itemRepo.softDeleteByScanIds(ctx, collectionId, listOf("scan-1", "scan-2"))).thenReturn(2L)

        // Act
        val req = Collection.RemoveItemsReq(collectionId = "coll-id", scanRecordIds = listOf("scan-1", "scan-2"))
        val result = service.removeItems(ctx, req)

        // Assert
        assertThat(result).isEqualTo(2L)
    }
}
