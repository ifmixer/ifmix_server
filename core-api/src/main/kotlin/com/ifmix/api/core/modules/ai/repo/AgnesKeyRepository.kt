package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.AgnesKey
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.babyfish.jimmer.sql.kt.ast.expression.or
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AgnesKeyRepository(sql: KSqlClient) : BaseAppCrudRepository<AgnesKey>(sql, AgnesKey::class) {

    fun findAllEnabled(ctx: SvcCtx): List<AgnesKey> =
        sql.createQuery(AgnesKey::class) {
            select(table)
        }.execute()

    fun findAvailable(ctx: SvcCtx, appId: UUID): List<AgnesKey> {
        val now = Instant.now()
        return sql.createQuery(AgnesKey::class) {
            where(table.get<UUID>("appId") eq appId)
            where(or(
                isNull(table.get<Instant?>("unavailableUntil")),
                table.get<Instant>("unavailableUntil") lt now
            ))
            select(table)
        }.execute()
    }

    fun markUnavailable(ctx: SvcCtx, keyId: UUID, until: Instant) {
        sql.createUpdate(AgnesKey::class) {
            where(table.getId<UUID>() eq keyId)
            set(table.get<Instant?>("unavailableUntil"), until)
            set(table.get<Instant>("updatedAt"), Instant.now())
        }.execute()
    }
}
