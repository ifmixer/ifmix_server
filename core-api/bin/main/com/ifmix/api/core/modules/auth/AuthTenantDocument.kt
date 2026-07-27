package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.db.BaseDocument
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 认证租户文档：一个外部身份提供商（如 Google、Apple）对应一个 auth_tenant。
 * 不软删、不分片（全局唯一）。
 */
@Document(collection = "auth_tenant")
class AuthTenantDocument : BaseDocument() {

    /** 租户级 Ed25519 JWT 私钥的 PEM 编码（PKCS#8）。 */
    var jwtPrivateKeyPem: String? = null

    /** 租户级 JWT 签发者标识（用于 JWT iss 字段）。 */
    var jwtIssuer: String? = null
}
