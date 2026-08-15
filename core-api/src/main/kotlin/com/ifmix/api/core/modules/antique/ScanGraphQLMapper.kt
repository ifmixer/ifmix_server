package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.graphql.common.type.ScanRecordType

/**
 * 将 ScanRecordDocument 转换为 GraphQL 展示类型。
 *
 * userDisplayName / userNotes 在文档中不存在，固定返回 null，未来可在扩展字段后回填。
 */
fun ScanRecordDocument.toScanRecordType(): ScanRecordType = ScanRecordType(
    id = this.id,
    scanId = this.scanId,
    imageUrl = this.imageUrl,
    status = this.status,
    resultJson = this.resultJson,
    tier = this.tier,
    userDisplayName = null,
    userNotes = null,
    collected = this.collected,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)
