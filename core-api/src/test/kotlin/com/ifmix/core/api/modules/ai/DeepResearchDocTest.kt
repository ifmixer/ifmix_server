package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.dto.ai.DeepResearchDocs
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * DeepResearch 异步化的纯函数校验（设计 §10.1/§10.3 的可离线部分）：
 * doc JSON 结构与 R2 key 格式；(created_at, id) 的 latest 新旧判定。
 */
class DeepResearchDocTest {

    @Test
    fun `doc has top-level premiumResult and snapshots in spec order`() {
        val scanSnapshot = linkedMapOf<String, Any?>("id" to "scan-1", "basicResult" to emptyMap<String, Any?>())
        val drSnapshot = linkedMapOf<String, Any?>("id" to "dr-1", "scanRecordId" to "scan-1")
        val premium = linkedMapOf<String, Any?>("verdict" to "乾隆官窑")

        val doc = DeepResearchDocs.buildDoc(
            docVersion = DeepResearchDocs.CURRENT_DOC_VERSION,
            promptVersion = "v10",
            scanRecordSnapshot = scanSnapshot,
            deepResearchSnapshot = drSnapshot,
            premiumResult = premium,
        )

        assertThat(DeepResearchDocs.CURRENT_DOC_VERSION).isEqualTo(1)
        assertThat(doc.keys.toList()).isEqualTo(
            listOf("docVersion", "promptVersion", "scanRecordSnapshot", "deepResearchSnapshot", "premiumResult"),
        )
        assertThat(doc.containsKey("scanRecordSnapshot")).isTrue()
        assertThat(doc["deepResearchSnapshot"]).isEqualTo(drSnapshot)
        // premiumResult 是顶级字段且为同一引用（前端只摘此字段）
        assertThat(doc["premiumResult"]).isEqualTo(premium)
    }

    @Test
    fun `object key uses hive-style partition with utc date and deepResearchId`() {
        // UTC 2026-10-02 23:30 → 2026/10/02（跨时区不漂移）
        val createdAt = Instant.parse("2026-10-02T23:30:00Z")
        val id = UUID.fromString("00000000-0000-0000-0000-00000000abcd")
        val key = DeepResearchDocs.objectKey("antique", id, createdAt)
        assertThat(key).isEqualTo("data/project=antique/type=deep_research/year=2026/month=10/day=02/$id.json")
    }

    @Test
    fun `latest comparison is (created_at, id) tuple order`() {
        val repo = ScanRecordRepository()
        val t1 = Instant.parse("2026-10-02T10:00:00Z")
        val t2 = Instant.parse("2026-10-02T11:00:00Z")
        val idA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val idB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

        // 晚时间胜
        assertThat(repo.isNewer(t2, idA, t1, idB)).isTrue()
        assertThat(repo.isNewer(t1, idA, t2, idB)).isFalse()
        // 同时间 id 兜底
        assertThat(repo.isNewer(t1, idB, t1, idA)).isTrue()
        assertThat(repo.isNewer(t1, idA, t1, idB)).isFalse()
        // 相等不算新（严格大于）
        assertThat(repo.isNewer(t1, idA, t1, idA)).isFalse()
    }
}
