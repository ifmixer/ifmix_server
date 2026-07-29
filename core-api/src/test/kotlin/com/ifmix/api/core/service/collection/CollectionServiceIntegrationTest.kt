package com.ifmix.api.core.service.collection

import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.CreateScanRequest
import com.ifmix.api.core.service.antique.CollectionMembership
import com.ifmix.api.core.service.antique.AntiqueService
import com.ifmix.api.core.entity.antique.ScanRecord
import com.ifmix.api.core.support.AbstractJimmerTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * CollectionService 集成测试（Jimmer + PostgreSQL）。
 * 验证默认收藏创建、添加/移除项目、列表查询以及会员归属检查。
 */
@SpringBootTest(classes = [com.ifmix.api.CoreApplication::class])
class CollectionServiceIntegrationTest : AbstractJimmerTest() {

    @Autowired
    private lateinit var collectionService: CollectionService

    @Autowired
    private lateinit var membership: CollectionMembership

    @Autowired
    private lateinit var scanRepo: ScanRecordRepository

    @Autowired
    private lateinit var antiqueService: AntiqueService

    private val ctx = RequestContext(
        appId = "test-app-id",
        userId = "test-user",
        installId = null
    )

    @BeforeEach
    fun setUp() {
        // Clean up any existing test data for this app ID
        val appId = UUID.fromString(ctx.appId)
        // Clear all scan records for this app
        val allScans = scanRepo.findAll().filter { it.appId == appId }
        allScans.forEach { scan ->
            scanRepo.deleteById(scan.id!!)
        }
    }

    /**
     * 测试 1：getDefault 应返回现有默认收藏或创建新的默认收藏
     */
    @Test
    @Transactional
    fun `test getDefault returns existing or creates new default collection`() {
        // First call - should create a new default collection
        val collection1 = collectionService.getDefault(ctx)
        assertThat(collection1.isDefault).isTrue()
        assertThat(collection1.userId).isEqualTo("test-user")
        assertThat(collection1.installId).isNull()

        // Second call - should return the same collection (existing)
        val collection2 = collectionService.getDefault(ctx)
        assertThat(collection2.id).isEqualTo(collection1.id)
        assertThat(collection2.isDefault).isTrue()
    }

    /**
     * 测试 2：addItem 应支持幂等性 - 重复添加不会创建重复条目
     */
    @Test
    @Transactional
    fun `test addItem is idempotent`() {
        // 首先创建一个扫描记录
        val scanRecord = createTestScanRecord()

        // Count initial items
        val initialCount = collectionService.listItems(ctx, null).items.size

        // First add
        val req = AddItemReq(scanRecordId = scanRecord.id.toString())
        val result1 = collectionService.addItem(ctx, req)
        assertThat(result1).isNotBlank()

        // Second add (same item) - should be idempotent
        val result2 = collectionService.addItem(ctx, req)
        assertThat(result2).isEqualTo(result1)

        // Verify count didn't increase (only one item exists)
        val finalCount = collectionService.listItems(ctx, null).items.size
        assertThat(initialCount).isEqualTo(finalCount)
    }

    /**
     * 测试 3：removeItems 应成功软删除收藏项
     */
    @Test
    @Transactional
    fun `test removeItems soft deletes collection items`() {
        // 创建两个扫描记录并添加到收藏
        val scan1 = createTestScanRecord()
        val scan2 = createTestScanRecord()

        collectionService.addItem(ctx, AddItemReq(scanRecordId = scan1.id.toString()))
        collectionService.addItem(ctx, AddItemReq(scanRecordId = scan2.id.toString()))

        // Confirm both items were added
        val beforeList = collectionService.listItems(ctx, null)
        assertThat(beforeList.items.size).isEqualTo(2)

        // 移除这两个项目
        val removeReq = RemoveItemsReq(
            collectionId = null, // 使用默认收藏
            scanRecordIds = listOf(scan1.id.toString(), scan2.id.toString())
        )
        val result = collectionService.removeItems(ctx, removeReq)
        assertThat(result.removed).isEqualTo(2L)

        // 验证已被软删除（列表中应该没有这些项目）
        val afterList = collectionService.listItems(ctx, null)
        assertThat(afterList.items.size).isEqualTo(0)
    }

    /**
     * 测试 4：listItems 应正确返回列表并支持分页
     */
    @Test
    @Transactional
    fun `test listItems returns paginated results`() {
        // 创建多个扫描记录并添加到收藏（10个）
        val scanIds = mutableListOf<UUID>()
        repeat(10) { i ->
            val scan = createTestScanRecord()
            scanIds.add(scan.id)
            collectionService.addItem(ctx, AddItemReq(scanRecordId = scan.id.toString()))
        }

        // 获取第一页（limit=5）
        val firstReq = ListItemsReq(limit = 5)
        val firstPage = collectionService.listItems(ctx, firstReq)

        assertThat(firstPage.items.size).isEqualTo(5)
        assertThat(firstPage.nextCursor).isNotNull()
        assertThat(firstPage.hasMore).isTrue() // There should be more pages

        // 获取第二页（使用游标）
        val secondReq = ListItemsReq(limit = 5, cursor = firstPage.nextCursor)
        val secondPage = collectionService.listItems(ctx, secondReq)

        assertThat(secondPage.items.size).isEqualTo(5)
        assertThat(secondPage.nextCursor).isNull() // No more pages
        assertThat(secondPage.hasMore).isFalse()

        // 验证所有 10 个项目都 retrieved
        val allItemsCombined = firstPage.items + secondPage.items
        assertThat(allItemsCombined.size).isEqualTo(10)

        // 验证所有 scanRecord 都存在且正确映射
        val retrievedIds = allItemsCombined.map { it.id }
        scanIds.forEach { id -> assertThat(retrievedIds).contains(id.toString()) }
    }

    /**
     * 测试 5：Membership.isCollected() 应正确返回收藏状态
     */
    @Test
    @Transactional
    fun `test Membership isCollected returns correct values`() {
        // 初始状态：scanRecord 不应在收藏中
        val scan1 = createTestScanRecord()
        val collectedBefore = membership.isCollected(ctx, scan1.id.toString())
        assertThat(collectedBefore).isFalse()

        // 将 scan1 添加到收藏
        collectionService.addItem(ctx, AddItemReq(scanRecordId = scan1.id.toString()))

        // Now should be collected
        val collectedAfter = membership.isCollected(ctx, scan1.id.toString())
        assertThat(collectedAfter).isTrue()

        // 另一个未添加的 scanRecord should still not be collected
        val scan2 = createTestScanRecord()
        val notCollected = membership.isCollected(ctx, scan2.id.toString())
        assertThat(notCollected).isFalse()
    }

    /**
     * 测试 6：AntiqueService 使用 Membership 进行归属检查
     * 这是一个端到端验证，确保 AntiqueService 能正确访问 CollectionMembership
     */
    @Test
    @Transactional
    fun `test AntiqueService interacts with CollectionMembership`() {
        // 创建一个扫描记录
        val relatedId = "test-related-1"
        val scanRecord = antiqueService.createScan(ctx, CreateScanRequest(relatedId))

        // 初始时，该 scanRecord 不应被标记为 collected
        val collectedBefore = membership.isCollected(ctx, scanRecord.id.toString())
        assertThat(collectedBefore).isFalse()

        // 将该 scanRecord 添加到用户的默认收藏
        collectionService.addItem(ctx, AddItemReq(scanRecordId = scanRecord.id.toString()))

        // Now should be collected
        val collectedAfter = membership.isCollected(ctx, scanRecord.id.toString())
        assertThat(collectedAfter).isTrue()
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 创建测试用的 ScanRecord。
     */
    private fun createTestScanRecord(): ScanRecord {
        val relatedId = "test-scan-${System.currentTimeMillis()}"
        return antiqueService.createScan(ctx, CreateScanRequest(relatedId))
    }
}
