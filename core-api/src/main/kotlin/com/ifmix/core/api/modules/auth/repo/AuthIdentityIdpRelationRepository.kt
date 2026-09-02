package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.AuthIdentityIdpRelation
import com.ifmix.core.api.entity.auth.appId
import com.ifmix.core.api.entity.auth.authIdentityId
import com.ifmix.core.api.entity.auth.deletedAt
import com.ifmix.core.api.entity.auth.idpIdentityId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthIdentityIdpRelationRepository {
    companion object { private val tpl = AppCrudRepoTemplate(AuthIdentityIdpRelation::class) }

    /** 按 (appId, idpIdentityId) 查该身份在此 app 下绑定的账号关系（反查方向）。 */
    fun findByAppAndIdpIdentity(mc: ModuleCtx, appId: UUID, idpIdentityId: UUID): AuthIdentityIdpRelation? {
        return mc.sql.createQuery(AuthIdentityIdpRelation::class) {
            where(table.appId eq appId)
            where(table.idpIdentityId eq idpIdentityId)
            where(table.deletedAt.isNull())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    /** 按 (appId, authIdentityId) 查账号名下第一个身份关系。 */
    fun findFirstByAuthIdentity(mc: ModuleCtx, appId: UUID, authIdentityId: UUID): AuthIdentityIdpRelation? {
        return mc.sql.createQuery(AuthIdentityIdpRelation::class) {
            where(table.appId eq appId)
            where(table.authIdentityId eq authIdentityId)
            where(table.deletedAt.isNull())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun save(mc: ModuleCtx, entity: AuthIdentityIdpRelation) = tpl.save(mc, entity)
}
