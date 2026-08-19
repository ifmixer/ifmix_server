package com.ifmix.api.core.modules.auth.repo

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant
import com.ifmix.api.core.modules.auth.entity.AuthTenantEntity
import com.ifmix.api.core.modules.auth.repo.AuthTenantRepo

/**
 * auth_tenant 读取：按 _id 查找。
 * 当前只有一个租户（全局），未来多租户时可按 tenantId 路由。
 */
class AuthTenantRepo(private val mongo: MongoTemplate) {

    fun findById(id: String): AuthTenantEntity? =
        mongo.findById(id, AuthTenantEntity::class.java)

    /** 返回默认/首个活跃租户；不存在则创建空文档。 */
    fun findOrCreateDefault(): AuthTenantEntity {
        var tenant = mongo.findOne(Query(), AuthTenantEntity::class.java)
        if (tenant == null) {
            tenant = AuthTenantEntity().apply { createdAt = Instant.now(); updatedAt = Instant.now() }
            mongo.insert(tenant)
        }
        return tenant
    }
}
