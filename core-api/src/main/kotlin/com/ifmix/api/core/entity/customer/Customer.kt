package com.ifmix.api.core.entity.customer

import com.ifmix.api.core.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 级 Customer（C 端用户）。匿名先行、登录转正、跨设备合并。
 */
@Entity
@Table(name = "customer")
interface Customer : BaseAppEntity {

    /** 是否匿名（未转正）。app 启动即建匿名 customer，登录后转 false。 */
    val anonymous: Boolean

    /** 合并方向：本 customer 已并入的目标 customer id（匿名 cur → existing）。 */
    val mergedTo: UUID?

    @Serialized
    val metadata: Map<String, Any?>?
}
