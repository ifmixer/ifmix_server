package com.ifmix.api.core.modules.media.repo

import com.ifmix.api.core.entity.media.UploadRecord
import com.ifmix.api.core.entity.media.appId
import com.ifmix.api.core.entity.media.customerId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.babyfish.jimmer.sql.ast.mutation.DeleteMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UploadRecordRepository {
    companion object { private val tpl = AppCrudRepoTemplate(UploadRecord::class) }

    fun save(mc: ModuleCtx, entity: UploadRecord) = tpl.save(mc, entity)

    /** 合并：把 fromCustomerId 名下上传记录归属改到 toCustomerId。返回改写行数。 */
    fun reassignOwner(mc: ModuleCtx, appId: UUID, fromCustomerId: UUID, toCustomerId: UUID): Int =
        mc.sql.createUpdate(UploadRecord::class) {
            where(table.appId eq appId)
            where(table.customerId eq fromCustomerId)
            set(table.customerId, toCustomerId)
        }.execute()

    /** 阶段 6：物理删除某批 customer 名下上传记录（避免孤儿行）。 */
    fun physicalDeleteByCustomers(mc: ModuleCtx, appId: UUID, customerIds: Collection<UUID>): Int {
        if (customerIds.isEmpty()) return 0
        return mc.sql.createDelete(UploadRecord::class) {
            setMode(DeleteMode.PHYSICAL)
            where(table.appId eq appId)
            where(table.customerId valueIn customerIds)
        }.execute()
    }
}
