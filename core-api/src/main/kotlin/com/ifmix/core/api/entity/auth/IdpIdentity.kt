package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * IDP 下的用户身份（全局，跨 app）。
 * 唯一键: (idpId, providerSubjectId)
 */
@Entity
@Table(name = "auth_idpidentity")
interface IdpIdentity : BaseEntity {

    /** provider 类型（10:email 20:phone 30:apple 40:google）。 */
    @Column(name = "provider_type")
    val providerType: Int

    /** 逻辑外键 → auth_idp（email/phone 等内建身份可无 idp，故可选）。 */
    val idpId: UUID?

    /** 第三方 subject（原 account_id / sub） */
    @Column(name = "provider_subject_id")
    val providerSubjectId: String

    val email: String?
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

    val phoneVerified: Boolean

    /** 密码哈希（社交身份为 null）。 */
    val password: String?

    @Serialized
    val profile: Map<String, Any?>?

    val loginIp: String?
}
