package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.CreatedAtProps
import com.ifmix.core.api.entity.common.IdpType
import com.ifmix.core.api.entity.common.UUIDProps
import org.babyfish.jimmer.sql.*

/**
 * 身份提供商（全局，跨 app）。
 * 一旦创建只能改 name/desc。
 */
@Entity
@Table(name = "auth_idp")
interface Idp : UUIDProps, CreatedAtProps {
    val name: String
    /** 10:apple 20:google */
    @Column(name = "idp_type")
    val idpType: IdpType
    /** 第三方平台标识（如 client_id） */
    val thirdId: String
    /** 各 provider 的密钥/配置 */
    @Serialized
    val config: Map<String, Any?>
    val desc: String?
}
