package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.scan.ScanRecord
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ScanQueries(
    private val repo: com.ifmix.api.core.modules.scan.repo.ScanRecordRepository,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? =
        repo.findById(svc(opCtx), opCtx.mustGetAppId(), id)

    fun findByCursorFiltered(opCtx: OperationContext, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> {
        val svcCtx = svc(opCtx)
        val appId = opCtx.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = repo.findByCursor(svcCtx, appId, collected, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.let { it.id.toString() },
            hasMore = hasMore,
        )
    }
}
