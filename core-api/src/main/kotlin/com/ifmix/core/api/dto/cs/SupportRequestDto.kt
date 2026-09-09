package com.ifmix.core.api.dto.cs

import com.ifmix.core.api.entity.common.MediaRef

/**
 * 创建工单请求体。身份（appId/customerId/installId）由 header 推导，客户端不可指定；
 * status 固定 OPEN、各回复/关闭时间戳固定为 null，客户端不可指定。
 */
data class CreateSupportRequestReq(
    val title: String,
    val message: String,
    /** 工单分类。默认 0。允许未登记码值。 */
    val category: Int = 0,
    val email: String? = null,
    val phone: String? = null,
    val attachments: List<MediaRef>? = null,
)

/** 「我的工单」游标列表请求。 */
data class ListSupportRequestsReq(
    val cursor: String? = null,
    val limit: Int? = null,
)
