package com.ifmix.core.api.entity.customer

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 级 Customer（C 端用户）。匿名先行、登录转正、跨设备合并。
 * 账号资料（姓名/邮箱/手机/metadata）归属 auth_identity，本表只保留 app 级身份与合并语义。
 */
@Entity
@Table(name = "customer")
interface Customer : BaseProjectEntity {

    /** 是否匿名（未转正）。app 启动即建匿名 customer，登录后转 false。 */
    val anonymous: Boolean

    /** 合并方向：本 customer 已并入的目标 customer id（匿名 cur → existing）。 */
    val mergedTo: UUID?

    /** 合并发生的时间（配合 mergedTo）。 */
    val mergedToAt: java.time.Instant?

    /** 逻辑外键 → auth_identity（app 级账号）。匿名 customer 未登录时为 null。 */
    @Column(name = "auth_identity_id")
    val authIdentityId: UUID?
}
