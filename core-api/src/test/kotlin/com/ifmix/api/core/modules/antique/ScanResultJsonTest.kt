package com.ifmix.api.core.modules.scan

import com.ifmix.api.core.model.scan.ScanStatus
import com.ifmix.api.core.modules.scan.dto.ScanResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.KotlinModule

/** ScanResult 的 snake_case 序列化 / 反序列化测试。 */
class ScanResultJsonTest {

    private val snakeMapper = tools.jackson.databind.json.JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
        .build()

    @Test
    fun `serializes to snake_case`() {
        val result = ScanResult(
            scanId = "abc-123",
            status = ScanStatus.COMPLETED,
            isAntique = true,
            name = "青花瓷瓶",
            description = "一件精美的明代青花瓷瓶",
            dynasty = "明代",
            yearFrom = 1368,
            yearTo = 1644,
            dynastyConfidence = 0.92,
            material = "高岭土",
            technique = "釉下彩绘",
            shape = "瓶形",
            heightCm = 25.5,
            widthCm = 12.0,
            weightG = 850.0,
            priceRange = "5000-10000元",
            priceMin = 5000.0,
            priceMax = 10000.0,
            priceCurrency = "CNY",
            valueConfidence = 0.75,
            authenticity = ScanResult.Authenticity.AUTHENTIC,
            authenticityConfidence = 0.88,
            condition = ScanResult.Condition.GOOD,
            score = 78,
            confidence = 0.85,
            tags = listOf("瓷器", "明代", "青花"),
            aliases = listOf("明青花"),
            colors = listOf("蓝色", "白色"),
            decorations = listOf("云龙纹"),
            flaws = listOf("微磕"),
            hasInscription = true,
            inscription = "大明宣德年制",
            texture = "光滑",
            authenticityNotes = null,
            restorationHistory = null,
            notes = "器型完整，底款清晰",
            errorMessage = null,
            analyzedAt = 1722038400000L,
            primaryCategory = "陶瓷",
            secondaryCategory = "瓷器",
            tertiaryCategory = "青花瓷",
            materials = listOf("高岭土", "钴料"),
            techniques = listOf("釉下彩绘", "高温烧制"),
        )

        val json = snakeMapper.writeValueAsString(result)

        // 验证关键字段为 snake_case
        assertThat(json).contains("\"scan_id\"")
        assertThat(json).contains("\"is_antique\"")
        assertThat(json).contains("\"name\":\"青花瓷瓶\"")
        assertThat(json).contains("\"year_from\"")
        assertThat(json).contains("\"year_to\"")
        assertThat(json).contains("\"height_cm\"")
        assertThat(json).contains("\"weight_g\"")
        assertThat(json).contains("\"price_range\"")
        assertThat(json).contains("\"description\"")
    }

    @Test
    fun `deserializes from snake_case json`() {
        val json = """{
            "scan_id": "test-001",
            "status": "COMPLETED",
            "is_antique": true,
            "name": "青铜鼎",
            "primary_category": "青铜器",
            "dynasty": "商代",
            "year_from": 1600,
            "year_to": 1046,
            "material": "青铜",
            "shape": "鼎形",
            "height_cm": 28.5,
            "weight_g": 15000.0,
            "condition": "FAIR",
            "score": 65,
            "confidence": 0.80,
            "tags": ["青铜", "商代"],
            "has_inscription": true,
            "inscription": "族徽铭文"
        }"""

        val result = snakeMapper.readValue(json, ScanResult::class.java)

        assertThat(result.scanId).isEqualTo("test-001")
        assertThat(result.status).isEqualTo(ScanStatus.COMPLETED)
        assertThat(result.isAntique).isTrue()
        assertThat(result.name).isEqualTo("青铜鼎")
        assertThat(result.primaryCategory).isEqualTo("青铜器")
        assertThat(result.dynasty).isEqualTo("商代")
        assertThat(result.yearFrom).isEqualTo(1600)
        assertThat(result.yearTo).isEqualTo(1046)
        assertThat(result.material).isEqualTo("青铜")
        assertThat(result.heightCm).isEqualTo(28.5)
        assertThat(result.weightG).isEqualTo(15000.0)
        assertThat(result.condition).isEqualTo(ScanResult.Condition.FAIR)
        assertThat(result.score).isEqualTo(65)
        assertThat(result.confidence).isEqualTo(0.80)
        assertThat(result.tags).containsExactly("青铜", "商代")
        assertThat(result.hasInscription).isTrue()
        assertThat(result.inscription).isEqualTo("族徽铭文")
    }

    @Test
    fun `null fields serialize as null`() {
        val result = ScanResult(
            scanId = "minimal",
            status = ScanStatus.PENDING,
        )
        val json = snakeMapper.writeValueAsString(result)
        assertThat(json).contains("\"is_antique\":null")
        assertThat(json).contains("\"name\":null")
        assertThat(json).contains("\"primary_category\":null")
    }

    @Test
    fun `empty lists serialize as empty arrays`() {
        val result = ScanResult(
            scanId = "lists-test",
            status = ScanStatus.COMPLETED,
            isAntique = true,
            name = "test",
        )
        val json = snakeMapper.writeValueAsString(result)
        assertThat(json).contains("\"aliases\":[]")
        assertThat(json).contains("\"tags\":[]")
        assertThat(json).contains("\"colors\":[]")
        assertThat(json).contains("\"decorations\":[]")
        assertThat(json).contains("\"flaws\":[]")
        assertThat(json).contains("\"materials\":[]")
        assertThat(json).contains("\"techniques\":[]")
    }
}
