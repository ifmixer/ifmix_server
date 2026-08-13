package com.ifmix.api.core.common.modules.ai.repo

import com.ifmix.api.core.common.entity.ai.AgnesKey
import com.ifmix.api.core.common.entity.ai.appId
import com.ifmix.api.core.common.entity.ai.id
import com.ifmix.api.core.common.entity.ai.unavailableUntil
import com.ifmix.api.core.common.entity.ai.updatedAt
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AgnesKeyRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<AgnesKey>(clusterRegistry, AgnesKey::class) {

    fun findAllEnabled(ctx: RepoContext): List<AgnesKey> {
        return sql(ctx).createQuery(AgnesKey::class) {
            select(table)
        }.execute()
    }

    fun findAvailable(ctx: RepoContext, appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return sql(ctx).createQuery(AgnesKey::class) {
            where(table.appId eq appId)
            where(
                or(
                    table.unavailableUntil.isNull(),
                    table.unavailableUntil lt now
                )
            )
            select(table)
        }.execute()
    }

    fun markUnavailable(ctx: RepoContext, keyId: UUID, until: Instant) {
        writerSql(ctx).createUpdate(AgnesKey::class) {
            where(table.id eq keyId)
            set(table.unavailableUntil, until)
            set(table.updatedAt, Instant.now())
        }.execute()
    }
}
