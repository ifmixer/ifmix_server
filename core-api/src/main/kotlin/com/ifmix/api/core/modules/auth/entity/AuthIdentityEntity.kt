package com.ifmix.api.core.modules.auth.entity

import com.ifmix.api.core.common.db.BaseEntity
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 租户级"人"+资料。authTenantId 分片；v1 不按 email/phone 唯一、不自动合并。
 */
@Document(collection = "auth_identity")
@CompoundIndexes(
    CompoundIndex(name = "identity_tenant_idx", def = "{'authTenantId': 1, '_id': 1}"),
    CompoundIndex(name = "identity_tenant_email_idx", def = "{'authTenantId': 1, 'email': 1}"),
    CompoundIndex(name = "identity_tenant_phone_idx", def = "{'authTenantId': 1, 'phone': 1}"),
)
class AuthIdentityEntity : BaseEntity() {
    var authTenantId: String? = null
    var rawEmail: String? = null
    var email: String? = null
    var rawPhone: String? = null
    var phone: String? = null
    var contactEmail: String? = null
    var displayName: String? = null
    var passwordHash: String? = null          // 预留
    var profile: Map<String, Any?>? = null
    var metadata: Map<String, Any?>? = null
}
