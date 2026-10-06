package com.ifmix.core.api.dto.storage

/**
 * presign download 协议入参（手写，替代 DGS generated type；字段与 schema/customer/media.graphqls
 * 的 `input PresignDownloadInput` 一致）。
 */
data class PresignDownloadInput(
    val imageKey: String,
    /** 签名有效期（秒）；缺省 3600，服务端封顶 24h、下限 60s。 */
    val durationSeconds: Int? = null,
)
