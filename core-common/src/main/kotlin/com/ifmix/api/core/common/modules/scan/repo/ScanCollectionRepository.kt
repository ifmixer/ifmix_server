package com.ifmix.api.core.common.modules.scan.repo

import com.ifmix.api.core.common.entity.scan.ScanCollection
import com.ifmix.api.core.common.entity.scan.appId
import com.ifmix.api.core.common.entity.scan.installId
import com.ifmix.api.core.common.entity.scan.isDefault
import com.ifmix.api.core.common.entity.scan.userId
import com.ifmix.api.core.common.infra.db.RepoContext
import com.ifmix.api.core.common.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.common.infra.repo.BaseAppCrudRepository
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScanCollectionRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<ScanCollection>(clusterRegistry, ScanCollection::class) {

    fun findDefault(ctx: RepoContext, appId: UUID, installId: UUID?, userId: UUID?): ScanCollection? {
        if (userId != null) {
            val byUser = sql(ctx).createQuery(ScanCollection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.userId eq userId)
                select(table)
            }.fetchOneOrNull()
            if (byUser != null) return byUser
        }
        if (installId != null) {
            return sql(ctx).createQuery(ScanCollection::class) {
                where(table.appId eq appId)
                where(table.isDefault eq true)
                where(table.installId eq installId)
                select(table)
            }.fetchOneOrNull()
        }
        return null
    }
}
