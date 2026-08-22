package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AppToIdpRelation
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.deletedAt
import com.ifmix.api.core.entity.auth.idpId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AppToIdpRelationRepository {
    companion object { private val tpl = AppCrudRepoTemplate(AppToIdpRelation::class) }

    /** 查该 app 是否启用了指定 IDP */
    fun findByAppAndIdp(mc: ModuleCtx, appId: UUID, idpId: UUID): AppToIdpRelation? {
        return mc.sql.createQuery(AppToIdpRelation::class) {
            where(table.appId eq appId)
            where(table.idpId eq idpId)
            where(table.deletedAt.isNull())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun save(mc: ModuleCtx, entity: AppToIdpRelation) = tpl.save(mc, entity)
}
