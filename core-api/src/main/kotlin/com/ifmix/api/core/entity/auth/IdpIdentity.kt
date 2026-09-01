package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.common.BaseEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * IDP 下的用户身份（全局，跨 app）。
 * 唯一键: (idpId, idpIdentityId)
 */
@Entity
@Table(name = "auth_idpidentity")
interface IdpIdentity : BaseEntity {

    /** 逻辑外键 → auth_idp */
    val idpId: UUID

    /** 第三方 account_id */
    val idpIdentityId: String

    val email: String?
    val emailVerified: Boolean
    val phone: String?

    @Serialized
    val profile: Map<String, Any?>?

    val loginIp: String?
}
