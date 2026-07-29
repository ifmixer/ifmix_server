package com.ifmix.api.core.service.antique

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
            status = ScanResult.Status.COMPLETED,
            isAntique = true,
            name = "青花瓷瓶",
            category = "陶瓷",
            subCategory = "青花",
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
            authenticity = "authentic",
            authenticityConfidence = 0.88,
            condition = "good",
            score = 78,
            confidence = 0.85,
            modelName = "gpt-4o",
            tags = listOf("瓷器", "明代", "青花"),
            aliases = listOf("明青花"),
            colors = listOf("蓝色", "白色"),
            decorations = listOf("云龙纹"),
            flaws = listOf("微磕"),
            hasInscription = true,
            inscription = "大明宣德年制",
            imageUrl = "https://example.com/photo.png",
            imageMimeType = "image/png",
            imageSize = "1920x1080",
            texture = "光滑",
            authenticityNotes = null,
            restorationHistory = null,
            notes = "器型完整，底款清晰",
            errorMessage = null,
            analyzedAt = "2026-07-27T00:00:00Z",
            apiKeyId = "key-****1234",
            modelLatencyMs = 3200L,
            label1 = "陶瓷",
            label2 = "瓷器",
            label3 = "青花瓷",
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
        assertThat(json).contains("\"model_name\"")
        assertThat(json).contains("\"api_key_id\"")
    }

    @Test
    fun `deserializes from snake_case json`() {
        val json = """{
            "scan_id": "test-001",
            "status": "COMPLETED",
            "is_antique": true,
            "name": "青铜鼎",
            "category": "青铜器",
            "dynasty": "商代",
            "year_from": 1600,
            "year_to": 1046,
            "material": "青铜",
            "shape": "鼎形",
            "height_cm": 28.5,
            "weight_g": 15000.0,
            "condition": "fair",
            "score": 65,
            "confidence": 0.80,
            "tags": ["青铜", "商代"],
            "has_inscription": true,
            "inscription": "族徽铭文"
        }"""

        val result = snakeMapper.readValue(json, ScanResult::class.java)

        assertThat(result.scanId).isEqualTo("test-001")
        assertThat(result.status).isEqualTo(ScanResult.Status.COMPLETED)
        assertThat(result.isAntique).isTrue()
        assertThat(result.name).isEqualTo("青铜鼎")
        assertThat(result.category).isEqualTo("青铜器")
        assertThat(result.dynasty).isEqualTo("商代")
        assertThat(result.yearFrom).isEqualTo(1600)
        assertThat(result.yearTo).isEqualTo(1046)
        assertThat(result.material).isEqualTo("青铜")
        assertThat(result.heightCm).isEqualTo(28.5)
        assertThat(result.weightG).isEqualTo(15000.0)
        assertThat(result.condition).isEqualTo("fair")
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
            status = ScanResult.Status.PENDING,
        )
        val json = snakeMapper.writeValueAsString(result)
        assertThat(json).contains("\"is_antique\":null")
        assertThat(json).contains("\"name\":null")
        assertThat(json).contains("\"category\":null")
    }

    @Test
    fun `empty lists serialize as empty arrays`() {
        val result = ScanResult(
            scanId = "lists-test",
            status = ScanResult.Status.COMPLETED,
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
