package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.CreatedAtProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 身份提供商（全局，跨 app）。
 * 一旦创建只能改 name/desc。
 */
@Entity
@Table(name = "auth_idp")
interface Idp : CreatedAtProps {
    @Id
    val id: UUID

    val name: String

    /** 10:apple 20:google */
    val providerType: Int

    /** 第三方平台标识（如 client_id） */
    val thirdId: String

    /** 各 provider 的密钥/配置 */
    @Serialized
    val config: Map<String, Any?>

    val desc: String?
}
