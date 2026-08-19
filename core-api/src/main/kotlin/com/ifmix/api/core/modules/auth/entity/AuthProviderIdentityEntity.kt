package com.ifmix.api.core.modules.auth.entity

import com.ifmix.api.core.common.db.BaseEntity
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * provider 维度用户。唯一 (authTenantId, provider, providerAccountId)。
 */
@Document(collection = "auth_provider_identity")
@CompoundIndexes(
    CompoundIndex(
        name = "provider_identity_uq",
        def = "{'authTenantId': 1, 'provider': 1, 'providerAccountId': 1}",
        unique = true,
    ),
    CompoundIndex(name = "provider_identity_identity_idx", def = "{'authTenantId': 1, 'authIdentityId': 1}"),
)
class AuthProviderIdentityEntity : BaseEntity() {
    var authTenantId: String? = null
    var authIdentityId: ObjectId? = null
    var provider: String? = null              // "google" | "apple"
    var providerAccountId: String? = null     // oauth sub
    var email: String? = null
    var emailVerified: Boolean = false
    var phone: String? = null
    var userMetadata: Map<String, Any?>? = null
    var providerMetadata: Map<String, Any?>? = null
    var loginIp: String? = null
    var loginInstallId: String? = null
    var loginAppId: String? = null
}
