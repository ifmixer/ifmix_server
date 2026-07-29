package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.entity.auth.AuthIdentity
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

/** Auth identity repository with custom queries */
@Component
class AuthIdentityRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthIdentity>(sql, AuthIdentity::class) {

    /** 按租户和邮箱查找身份（临时实现，Task 5 将优化为 Jimmer 查询 DSL） */
    fun findByTenantAndEmail(tenantId: String, email: String): AuthIdentity? {
        // Temporary: fetch all and filter (will be optimized in Task 5)
        val all = findAll()
        return all.firstOrNull { it.authTenant.id?.toString() == tenantId && it.email == email }
    }
}
