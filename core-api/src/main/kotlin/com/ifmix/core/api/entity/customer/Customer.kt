package com.ifmix.core.api.entity.customer

import com.ifmix.core.api.entity.common.BaseAppEntity
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

    /** 合并发生的时间（配合 mergedTo）。 */
    val mergedToAt: java.time.Instant?

    @Column(name = "first_name")
    val firstName: String?

    @Column(name = "last_name")
    val lastName: String?

    val email: String?

    @Column(name = "email_verified")
    val emailVerified: Boolean

    /** 手机号国际区号（如 "86"），不含 "+"。 */
    @Column(name = "phone_calling_code")
    val phoneCallingCode: String?

    /** ISO 3166-1 alpha-2 国家码（如 "CN"）。 */
    @Column(name = "phone_country_code")
    val phoneCountryCode: String?

    /** 国内号码部分（不含区号）。 */
    @Column(name = "phone_national_number")
    val phoneNationalNumber: String?

    @Column(name = "phone_verified")
    val phoneVerified: Boolean

    @Serialized
    val metadata: Map<String, Any?>?
}
