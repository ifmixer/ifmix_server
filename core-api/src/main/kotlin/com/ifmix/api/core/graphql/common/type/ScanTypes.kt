package com.ifmix.api.core.graphql.common.type

import java.time.Instant

/** GraphQL 层扫描记录展示类型。 */
data class ScanRecordType(
    val id: String,
    val scanId: String?,
    val imageUrl: String?,
    val status: String?,
    val resultJson: String?,
    val tier: String?,
    val userDisplayName: String?,
    val userNotes: String?,
    val collected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 带游标的扫描记录分页连接。 */
data class ScanConnection(
    val items: List<ScanRecordType>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** 预签名上传结果。 */
data class PresignUploadResult(
    val mediaId: String,
    val uploadUrl: String,
    val imageKey: String,
    val downloadUrl: String,
)

/** 预签名下载结果。 */
data class PresignDownloadResult(val downloadUrl: String)
