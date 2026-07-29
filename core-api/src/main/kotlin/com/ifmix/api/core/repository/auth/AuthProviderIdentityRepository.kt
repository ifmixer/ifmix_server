package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.entity.auth.AuthProviderIdentity
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.util.UUID

/** Provider identity repository with custom queries */
@Component
class AuthProviderIdentityRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthProviderIdentity>(sql, AuthProviderIdentity::class) {

    /** 按 tenantId + provider + providerAccountId 查找（临时实现） */
    fun findByProviderAndAccountId(tenantId: String, provider: String, providerAccountId: String): AuthProviderIdentity? {
        // Temporary: fetch all and filter (to be optimized in Task 5)
        return findAll().firstOrNull { it.authTenant.id.toString() == tenantId && it.provider == provider && it.providerAccountId == providerAccountId }
    }

    /** 上sert：存在则返回，否则创建新的（临时实现） */
    fun upsert(
        tenantId: String,
        provider: String,
        providerAccountId: String,
        identityId: UUID,
        email: String?,
        emailVerified: Boolean,
        phone: String?,
        userMetadata: Map<String, Any?>?,
        providerMetadata: Map<String, Any?>?,
        loginIp: String?,
        loginInstallId: String?,
        loginAppId: String?,
        appUserUuid: UUID?, // Added to link to AppUser if needed
    ): AuthProviderIdentity {
        val existing = findByProviderAndAccountId(tenantId, provider, providerAccountId)
        if (existing != null) return existing

        // Create new - using the simple save method from base repository
        // Note: In a full implementation, you would use sql.create(...) with proper draft construction
        throw UnsupportedOperationException("upsert not fully implemented")
    }
}
