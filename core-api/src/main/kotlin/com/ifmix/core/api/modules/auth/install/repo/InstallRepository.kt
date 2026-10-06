package com.ifmix.core.api.modules.auth.install.repo

import com.ifmix.core.api.entity.auth.install.Install
import com.ifmix.core.api.entity.auth.install.fcmToken
import com.ifmix.core.api.entity.auth.install.fcmTokenValid
import com.ifmix.core.api.entity.auth.install.id
import com.ifmix.core.api.entity.auth.install.projectId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
open class InstallRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(Install::class, UUID::class) }

    open fun save(mc: ModuleCtx, entity: Install): Boolean = tpl.save(mc, entity)

    /** 按 (projectId, id) 查设备。id 即 installId（PK = API installId = JWT iid）。 */
    fun findById(mc: ModuleCtx, projectId: String, id: UUID): Install? =
        mc.sql.createQuery(Install::class) {
            where(table.projectId eq projectId)
            where(table.id eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** 仅当数据库仍持有发送时的旧 token 才标记失效，避免 token 轮换竞态误伤。 */
    fun invalidateFcmToken(mc: ModuleCtx, projectId: String, installId: UUID, sentToken: String): Int =
        mc.sql.createUpdate(Install::class) {
            where(table.projectId eq projectId)
            where(table.id eq installId)
            where(table.fcmToken eq sentToken)
            set(table.fcmTokenValid, false)
        }.execute()
}
