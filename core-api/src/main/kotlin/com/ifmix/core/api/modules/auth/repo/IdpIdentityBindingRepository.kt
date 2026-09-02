package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.IdpIdentityBinding
import com.ifmix.core.api.entity.auth.actorId
import com.ifmix.core.api.entity.auth.actorType
import com.ifmix.core.api.entity.auth.appId
import com.ifmix.core.api.entity.auth.deletedAt
import com.ifmix.core.api.entity.auth.idpIdentityId
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class IdpIdentityBindingRepository {
    companion object { private val tpl = AppCrudRepoTemplate(IdpIdentityBinding::class) }

    /** 按 (appId, idpIdentityId) 查找未删除的绑定关系 */
    fun findByAppAndIdpIdentity(mc: ModuleCtx, appId: UUID, idpIdentityId: UUID): IdpIdentityBinding? {
        return mc.sql.createQuery(IdpIdentityBinding::class) {
            where(table.appId eq appId)
            where(table.idpIdentityId eq idpIdentityId)
            where(table.deletedAt.isNull())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    /** 查 customer 主体绑定的第一个 idpIdentity 关系 */
    fun findFirstByCustomer(mc: ModuleCtx, appId: UUID, customerId: UUID): IdpIdentityBinding? {
        return mc.sql.createQuery(IdpIdentityBinding::class) {
            where(table.appId eq appId)
            where(table.actorType eq AuthJwtService.ACTOR_CUSTOMER)
            where(table.actorId eq customerId)
            where(table.deletedAt.isNull())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun save(mc: ModuleCtx, entity: IdpIdentityBinding) = tpl.save(mc, entity)
}
