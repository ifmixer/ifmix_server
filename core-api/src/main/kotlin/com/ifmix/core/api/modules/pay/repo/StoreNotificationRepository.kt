package com.ifmix.core.api.modules.pay.repo

import com.ifmix.core.api.entity.pay.StoreNotification
import com.ifmix.core.api.entity.pay.projectId
import com.ifmix.core.api.entity.pay.id
import com.ifmix.core.api.entity.pay.platform
import com.ifmix.core.api.entity.pay.processed
import com.ifmix.core.api.entity.pay.purchaseToken
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository

@Repository
class StoreNotificationRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(StoreNotification::class) }

    fun existsByPlatformAndToken(mc: ModuleCtx, platform: String, purchaseToken: String): Boolean {
        return mc.sql.createQuery(StoreNotification::class) {
            where(table.platform eq platform)
            where(table.purchaseToken eq purchaseToken)
            where(table.processed eq true)
            select(table)
        }.limit(1).execute().isNotEmpty()
    }

    fun save(mc: ModuleCtx, entity: StoreNotification) = tpl.save(mc, entity)
}
