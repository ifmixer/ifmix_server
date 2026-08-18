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
        val FIELDS: Map<String, TableField<*, *>> = mapOf(
            "id" to CORE_SCAN_RECORD.ID,
            "appId" to CORE_SCAN_RECORD.APP_ID,
            "status" to CORE_SCAN_RECORD.STATUS,
            "collected" to CORE_SCAN_RECORD.COLLECTED,
            "lang" to CORE_SCAN_RECORD.LANG,
            "country" to CORE_SCAN_RECORD.COUNTRY,
            "currency" to CORE_SCAN_RECORD.CURRENCY,
            "userDisplayName" to CORE_SCAN_RECORD.USER_DISPLAY_NAME,
            "userNotes" to CORE_SCAN_RECORD.USER_NOTES,
            "clientIp" to CORE_SCAN_RECORD.CLIENT_IP,
            "createdAt" to CORE_SCAN_RECORD.CREATED_AT,
            "updatedAt" to CORE_SCAN_RECORD.UPDATED_AT,
        )
    }
}
