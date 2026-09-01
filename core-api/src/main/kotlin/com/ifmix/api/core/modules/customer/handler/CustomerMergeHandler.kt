package com.ifmix.api.core.modules.customer.handler

import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.modules.ai.repo.ScanCollectionRepository
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import com.ifmix.api.core.modules.customer.repo.CustomerRepository
import com.ifmix.api.core.modules.demo.repo.TodoRepository
import com.ifmix.api.core.modules.media.repo.UploadRecordRepository
import com.ifmix.api.core.modules.pay.repo.SubscriptionRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 账号合并（customer 专有）。方向硬编码「匿名 cur → existing」，绝不反向（R1）。
 *
 * 由 login 在其 GlobalTxRunner 事务内同步调用——必须与登录同事务，
 * 因为要返回 existing 的 token 且要保证资源迁移与转正原子提交。
 * 不放 @Async 监听器。
 */
@Component
class CustomerMergeHandler(
    private val scanRecordRepo: ScanRecordRepository,
    private val scanCollectionRepo: ScanCollectionRepository,
    private val uploadRecordRepo: UploadRecordRepository,
    private val todoRepo: TodoRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val customerRepo: CustomerRepository,
) {
    /**
     * 合并匿名 cur → existing。调用方须已确认 cur 匿名且 existing != cur（判定表第三分支）。
     * 步骤：
     *  1. 记录 existing 合并前的默认收藏夹（用于 is_default 去重保留）。
     *  2. 改写 cur 名下资源 customerId → existing（5 张表）。
     *  3. is_default 去重：existing 名下只保留一个 isDefault=true。
     *  4. cur 置 mergedTo=existing（tombstone）。
     */
    fun merge(mc: ModuleCtx, appId: UUID, curId: UUID, existingId: UUID) {
        require(curId != existingId) { "merge: curId must differ from existingId" }

        // 1. 合并前捕获 existing 原有的默认收藏夹 id（若有），作为去重保留目标
        val existingDefaultId = scanCollectionRepo.findDefault(mc, appId, existingId)?.id

        // 2. 改写 cur 名下资源归属 → existing
        scanRecordRepo.reassignOwner(mc, appId, curId, existingId)
        scanCollectionRepo.reassignOwner(mc, appId, curId, existingId)
        uploadRecordRepo.reassignOwner(mc, appId, curId, existingId)
        todoRepo.reassignOwner(mc, appId, curId, existingId)
        subscriptionRepo.reassignOwner(mc, appId, curId, existingId)

        // 3. is_default 去重：existing 每 customer 只能一个默认收藏夹。
        //    existing 原有默认优先保留；existing 原本无默认则保留迁移过来的其中一个。
        val keepId = existingDefaultId
            ?: scanCollectionRepo.findDefault(mc, appId, existingId)?.id
        if (keepId != null) {
            scanCollectionRepo.demoteOtherDefaults(mc, appId, existingId, keepId)
        }

        // 4. cur 置 mergedTo=existing（tombstone，清理任务回收）
        customerRepo.markMerged(mc, appId, curId, existingId)
    }
}
