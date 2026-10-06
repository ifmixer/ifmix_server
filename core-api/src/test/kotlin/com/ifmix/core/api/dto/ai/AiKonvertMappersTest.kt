package com.ifmix.core.api.dto.ai

import com.ifmix.core.api.entity.ai.ImageRef
import com.ifmix.core.api.entity.ai.ScanDeepResearch
import com.ifmix.core.api.entity.ai.ScanRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [AiKonvertMappers] 生成实现（AiKonvertMappersImpl）与原手写 AiApiMappers 的逐字段等价断言
 *（konvert-rollout-server.md K3 验收 §3.3）：
 * - Instant → String 均为 [Instant.toString] ISO-8601 UTC（内置 InstantToStringConverter）；
 * - [AiKonvertMappersImpl.toLiteRes] / toListRes 的 basicResult 为 constant null 占位
 *   ——生成代码不触碰 source.basicResult（稀疏实体，列表/批量路径不加载 JSONB 大字段）；
 * - latestDeepResearch 三个 ScanRecord 映射均为 constant null 占位，由调用方 copy 补齐（§0.5 第 2 条定式）。
 */
class AiKonvertMappersTest {

    private val now = Instant.parse("2026-10-06T08:30:00Z")
    private val later = Instant.parse("2026-10-06T09:00:00Z")

    private fun scanRecord(
        withBasicResult: Boolean = true,
        withNulls: Boolean = false,
    ): ScanRecord = ScanRecord {
        id = UUID.randomUUID()
        projectId = "ifmix-demo"
        images = listOf(ImageRef(key = "k1", category = 0), ImageRef(key = "k2", category = null))
        if (withBasicResult) basicResult = mapOf("scan_status" to "SUCCESS")
        status = 30
        errorCode = if (withNulls) null else "E100"
        if (withNulls) {
            locale = null
            country = null
            currency = null
            userDisplayName = null
            userNotes = null
        } else {
            locale = "zh-CN"
            country = "CN"
            currency = "CNY"
            userDisplayName = "u"
            userNotes = "n"
        }
        collected = true
        isPublic = false
        hasDeepSearch = true
        createdAt = now
        updatedAt = later
    }

    private fun deepResearch(): ScanDeepResearch = ScanDeepResearch {
        id = UUID.randomUUID()
        projectId = "ifmix-demo"
        scanRecordId = UUID.randomUUID()
        premiumResult = mapOf("answer" to "a")
        createdAt = now
        updatedAt = later
    }

    // ===== ImageRef → ImageRefRes =====

    @Test
    fun `imageRef maps key and category with null passthrough`() {
        val res = AiKonvertMappersImpl.toRes(ImageRef(key = "k", category = 30))
        assertEquals("k", res.key)
        assertEquals(30, res.category)

        val nullCategory = AiKonvertMappersImpl.toRes(ImageRef(key = "k2", category = null))
        assertNull(nullCategory.category)
    }

    // ===== ScanDeepResearch → ScanDeepResearchRes =====

    @Test
    fun `deepResearch maps all fields and instants to iso strings`() {
        val source = deepResearch()

        val res = AiKonvertMappersImpl.toRes(source)

        assertEquals(source.id, res.id)
        assertEquals(source.scanRecordId, res.scanRecordId)
        assertEquals(mapOf("answer" to "a"), res.premiumResult)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
    }

    // ===== ScanRecord → ScanRecordRes（详情） =====

    @Test
    fun `detail maps all fields, images element-wise, basicResult passthrough`() {
        val source = scanRecord()

        val res = AiKonvertMappersImpl.toDetailRes(source)

        assertEquals(source.id, res.id)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
        assertEquals(false, res.isPublic)
        assertEquals(30, res.status)
        assertEquals("E100", res.errorCode)
        assertEquals("zh-CN", res.locale)
        assertEquals("CN", res.country)
        assertEquals("CNY", res.currency)
        assertEquals("u", res.userDisplayName)
        assertEquals("n", res.userNotes)
        assertEquals(true, res.collected)
        assertEquals(true, res.hasDeepSearch)
        assertEquals(2, res.images!!.size)
        assertEquals("k1", res.images!![0].key)
        assertEquals(0, res.images!![0].category)
        assertNull(res.images!![1].category)
        assertEquals(mapOf("scan_status" to "SUCCESS"), res.basicResult)
        // 占位：latestDeepResearch 由调用方（AiQueryService）预取后 copy 补齐
        assertNull(res.latestDeepResearch)
    }

    @Test
    fun `detail nullable fields pass through as null`() {
        val res = AiKonvertMappersImpl.toDetailRes(scanRecord(withNulls = true))

        assertNull(res.errorCode)
        assertNull(res.locale)
        assertNull(res.country)
        assertNull(res.currency)
        assertNull(res.userDisplayName)
        assertNull(res.userNotes)
    }

    // ===== ScanRecord → ScanRecordRes（lite，稀疏实体安全） =====

    @Test
    fun `lite keeps basicResult constant null regardless of source value and caller copies latestDeepResearch`() {
        val source = scanRecord(withBasicResult = true)
        val dr = deepResearch()

        // 生成实现绝不触碰 source.basicResult（断言占位 null）；latestDeepResearch 调用方 copy 补齐
        val res = AiKonvertMappersImpl.toLiteRes(source)
            .copy(latestDeepResearch = AiKonvertMappersImpl.toRes(dr))

        assertNull(res.basicResult)
        assertEquals(dr.id, res.latestDeepResearch!!.id)
        assertEquals(mapOf("answer" to "a"), res.latestDeepResearch!!.premiumResult)
        assertEquals(2, res.images!!.size)
        assertEquals("E100", res.errorCode)
    }

    // ===== ScanRecord → ScanRecordListRes（列表） =====

    @Test
    fun `list maps list-view fields with basicResult constant null`() {
        val source = scanRecord(withNulls = true)

        val res = AiKonvertMappersImpl.toListRes(source)

        assertEquals(source.id, res.id)
        assertEquals(2, res.images!!.size)
        assertEquals(30, res.status)
        assertNull(res.errorCode)
        assertNull(res.basicResult)
        assertNull(res.locale)
        assertNull(res.userNotes)
        assertEquals(true, res.collected)
        assertEquals(false, res.isPublic)
        assertEquals(now.toString(), res.createdAt)
        assertEquals(later.toString(), res.updatedAt)
    }
}
