package com.ifmix.api.core.modules.ai.entity

import com.ifmix.api.core.common.db.BaseAppEntity
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * scan_records 集合。
 *
 * 按 appId 分片，支持软删。userId/installId 用于登录后归并匿名记录。
 */
@Document(collection = "scan_records")
class ScanRecordEntity : BaseAppEntity() {

    @Indexed
    var scanId: String? = null

    var imageUrl: String? = null

    /** 扫描结果 JSON（字符串形式），由 ScanRunner 写入。 */
    var resultJson: String? = null

    var status: String? = null

    var tier: String? = null

    var clientIp: String? = null

    /** 可选：关联的 todo / task ID。 */
    var relatedId: String? = null

    /** 归属用户 ID（ObjectId hex 字符串），登录后由 MergeOnLoginListener 回填。 */
    var userId: String? = null

    /** 归属安装 ID（ObjectId hex 字符串），匿名阶段即记录。 */
    var installId: String? = null

    /** 是否已被收藏（在默认夹中），由 CollectionService.addItem() 更新。 */
    var collected: Boolean = false
}

/** ScanRecordEntity → GraphQL ScanRecord 转换。 */
fun ScanRecordEntity.toScanRecord(): com.ifmix.api.core.graphql.generated.types.ScanRecord =
    com.ifmix.api.core.graphql.generated.types.ScanRecord(
        id = this.id?.toHexString() ?: "",
        scanId = this.scanId,
        imageUrl = this.imageUrl,
        status = this.status,
        resultJson = this.resultJson,
        tier = this.tier,
        collected = this.collected,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )
