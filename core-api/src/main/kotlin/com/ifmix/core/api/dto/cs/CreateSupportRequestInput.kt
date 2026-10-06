package com.ifmix.core.api.dto.cs

import com.ifmix.core.api.entity.common.MediaRef
import io.mcarle.konvert.api.KonvertTo

/**
 * 创建工单协议入参（手写，字段与 schema/customer/cs.graphqls 的 `input CreateSupportRequestInput` 一致）。
 * 身份/installId/locale 由 ctx 推导；status 固定 OPEN，客户端不可指定。
 */
@KonvertTo(CreateSupportRequestReq::class)
data class CreateSupportRequestInput(
    val title: String,
    val message: String,
    /** 工单分类 Int 码，默认 0，允许未登记值。 */
    val category: Int = 0,
    val email: String? = null,
    val phone: String? = null,
    /** 附件。元素形状与 common.graphqls 的 `input MediaInput` 一致，复用 [MediaRef]（key/type/category）。 */
    val attachments: List<MediaRef>? = null,
)
