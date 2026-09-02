package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*

/**
 * App 级账号中枢：Customer.authIdentityId 指向本表；
 * 与 IdpIdentity 的 M:N 绑定关系存于 auth_identity_to_idpidentity_relation。
 * 承载账号权威资料（姓名/邮箱/手机/metadata）与密码。
 */
@Entity
@Table(name = "auth_identity")
interface AuthIdentity : BaseAppEntity {

    /** 密码哈希（未设密码为 null）。 */
    val password: String?

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
