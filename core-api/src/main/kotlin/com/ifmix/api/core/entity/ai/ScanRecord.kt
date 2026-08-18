package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.ImageRef
import com.ifmix.api.core.jooq.tables.CoreScanRecord.Companion.CORE_SCAN_RECORD
import org.jooq.TableField
import java.time.Instant
import java.util.UUID

/**
 * Scan 领域模型。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射：
 *   imageKeys -> IMAGE_KEYS (JSONB)
 *   resultJson -> RESULT_JSON (JSONB)
 */
data class ScanRecord(
    val id: UUID,
    val appId: UUID,
    val imageKeys: List<ImageRef>,           // DB: IMAGE_KEYS (JSONB)
    val basicResult: Map<String, Any?>? = null,    // DB: BASIC_RESULT (JSONB)
    val premiumResult: Map<String, Any?>? = null,  // DB: PREMIUM_RESULT (JSONB)
    val status: Int,
    val clientIp: String? = null,
    val lang: String? = null,
    val country: String? = null,
    val currency: String? = null,
    val userDisplayName: String? = null,
    val userNotes: String? = null,
    val collected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
) {
    /** Aliased as [imageKeys] — exposes the same list under the GraphQL field name. */
    val images: List<ImageRef> get() = imageKeys

    /** 扫描状态编码 */
    object Status {
        const val UNKNOWN = 0
        const val PENDING = 100
        const val PROCESSING = 110
        const val COMPLETED = 200
        const val FAILED = 300
        fun isTerminal(code: Int) = code >= COMPLETED
    }

    companion object {
        /** Kotlin 属性名 → jOOQ TableField。供动态 filter/sort 使用。 */
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            ScanRecord::id.name to CORE_SCAN_RECORD.ID,
            ScanRecord::appId.name to CORE_SCAN_RECORD.APP_ID,
            ScanRecord::status.name to CORE_SCAN_RECORD.STATUS,
            ScanRecord::collected.name to CORE_SCAN_RECORD.COLLECTED,
            ScanRecord::lang.name to CORE_SCAN_RECORD.LANG,
            ScanRecord::country.name to CORE_SCAN_RECORD.COUNTRY,
            ScanRecord::currency.name to CORE_SCAN_RECORD.CURRENCY,
            ScanRecord::userDisplayName.name to CORE_SCAN_RECORD.USER_DISPLAY_NAME,
            ScanRecord::userNotes.name to CORE_SCAN_RECORD.USER_NOTES,
            ScanRecord::clientIp.name to CORE_SCAN_RECORD.CLIENT_IP,
            ScanRecord::createdAt.name to CORE_SCAN_RECORD.CREATED_AT,
            ScanRecord::updatedAt.name to CORE_SCAN_RECORD.UPDATED_AT,
        )
    }
}
