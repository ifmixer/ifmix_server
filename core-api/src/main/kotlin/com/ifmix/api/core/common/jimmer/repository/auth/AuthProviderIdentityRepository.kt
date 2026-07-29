package com.ifmix.api.core.common.jimmer.repository.auth

import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AuthProviderIdentity
import com.ifmix.api.core.common.jimmer.entity.auth.AuthIdentity
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Provider identity repository with custom upsert() method */
@Component
class AuthProviderIdentityRepository(
    private val sql: KSqlClient,
    private val identityRepo: AuthIdentityRepository,
) : BaseCrudRepository<AuthProviderIdentity>(sql, AuthProviderIdentity::class) {

    /**
     * upsert: Find or create identity based on provider account.
     Uses AuthIdentityRepository to manage identities.
     Returns authIdentityId as String.
     */
    fun upsert(tenantId: String, provider: String, accountId: String?, email: String?,
               emailVerified: Boolean, phone: String?, userMetadata: Map<String, Any?>?,
               loginIp: String?, loginInstallId: String?, loginAppId?: String): String {
        val now = Instant.now()
        val tenantUUID = try { UUID.fromString(tenantId) } catch (e: Exception) { throw IllegalArgumentException("Invalid tenantId") }

        // Find existing provider identity by tenant+provider+accountId
        val all = this.findAll()
        val existing = all.firstOrNull { pi ->
            pi.authTenant.id == tenantUUID &&
            pi.provider == provider &&
            pi.providerAccountId == accountId
        }

        if (existing != null) {
            return existing.authIdentity.id.toString()
        }

        // Not found - create new auth_identity first
        val identityId = UUID.randomUUID().toString()
        val identityInput: Input<AuthIdentity> = sql.input(AuthIdentity::class.java) {
            set("id", identityId)
            set("authTenantId", tenantUUID)
            set("rawEmail", email)
            set("email", email?.let { com.ifmix.api.core.common.auth.EmailNormalize.normalizeEmail(it) })
            set("rawPhone", phone)
            set("phone", phone?.let { com.ifmix.api.core.common.auth.EmailNormalize.normalizePhone(it) })
            set("displayName", userMetadata?.get("name") as? String)
            set("profile", userMetadata)
            set("metadata", emptyMap<Any, Any>())
            set("createdAt", now)
            set("updatedAt", now)
        }
        identityRepo.insert(identityInput)

        // Then create auth_provider_identity
        val providerIdentityId = UUID.randomUUID().toString()
        val providerIdentityInput: Input<AuthProviderIdentity> = sql.input(AuthProviderIdentity::class.java) {
            set("id", providerIdentityId)
            set("authTenantId", tenantUUID)
            set("authIdentityId", UUID(identityId))
            set("provider", provider)
            set("providerAccountId", accountId!!)
            set("email", email)
            set("emailVerified", emailVerified)
            set("phone", phone)
            set("userMetadata", userMetadata)
            set("providerMetadata", emptyMap<Any, Any>())
            set("loginIp", loginIp)
            set("loginInstallId", loginInstallId)
            set("loginAppId", loginAppId)
            set("createdAt", now)
            set("updatedAt", now)
        }
        save(providerIdentityInput)

        return identityId
    }
}