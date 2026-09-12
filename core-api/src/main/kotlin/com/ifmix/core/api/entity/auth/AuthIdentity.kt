package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*

/** 登录方式编码。typealias（Int 全链路透传），码表见 [LoginMethods]。 */
typealias LoginMethod = Int

/** 登录方式码表（0 保留，从 10 起步长 10）。 */
object LoginMethods {
    const val EMAIL: LoginMethod = 10
    const val PHONE: LoginMethod = 20
    const val IDP: LoginMethod = 30
}

/**
 * App 级账号中枢：Customer.authIdentityId 指向本表；
 * 与 IdpIdentity 的 M:N 绑定关系存于 auth_identity_to_idpidentity_relation。
 * 承载账号权威资料（姓名/邮箱/手机/metadata）与密码。
 */
@Entity
@Table(name = "auth_identity")
interface AuthIdentity : BaseProjectEntity {

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

    /** 最近一次登录时间。 */
    @Column(name = "last_login_at")
    val lastLoginAt: java.time.Instant?

    /** 最近一次登录方式（10:email 20:phone 30:idp）。 */
    @Column(name = "last_login_method")
    val lastLoginMethod: LoginMethod?

    /** 最近一次登录用的 idp 身份（idp 登录时非空；email/phone 登录为 null）。 */
    @Column(name = "last_login_idp_identity_id")
    val lastLoginIdpIdentityId: java.util.UUID?

    /** 最近一次登录 IP（可能取不到，故可空）。 */
    @Column(name = "last_login_ip")
    val lastLoginIp: String?

    @Serialized
    val metadata: Map<String, Any?>?
}
