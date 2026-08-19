package com.ifmix.api.core.modules.ai.repo

import com.ifmix.api.core.entity.ai.ScanCollection
import com.ifmix.api.core.entity.ai.appId
import com.ifmix.api.core.entity.ai.isDefault
import com.ifmix.api.core.entity.ai.userId
import com.ifmix.api.core.entity.ai.installId
import com.ifmix.api.core.entity.ai.ScanCollection.appId
import com.ifmix.api.core.entity.ai.ScanCollection.isDefault
import com.ifmix.api.core.entity.ai.ScanCollection.userId
import com.ifmix.api.core.entity.ai.ScanCollection.installId
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanCollection>(sql, ScanCollection::class) {

    fun findDefault(ctx: SvcCtx, appId: UUID, installId: UUID?, userId: String?): ScanCollection? {
        if (userId != null) {
            val result = sql.createQuery(ScanCollection::class) {
                where(appId eq appId)
                where(isDefault eq true)
                where(userId eq userId)
                select(table)
            }.limit(1).execute().firstOrNull()
            if (result != null) return result
        }
        if (installId != null) {
            return sql.createQuery(ScanCollection::class) {
                where(appId eq appId)
                where(isDefault eq true)
                where(installId eq installId)
                select(table)
            }.limit(1).execute().firstOrNull()
        }
        return null
    }
}
