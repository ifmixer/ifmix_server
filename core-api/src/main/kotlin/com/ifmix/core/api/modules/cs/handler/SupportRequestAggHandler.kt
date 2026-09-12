package com.ifmix.core.api.modules.cs.handler

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.cs.CreateSupportRequestReq
import com.ifmix.core.api.dto.cs.ListSupportRequestsReq
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.entity.cs.SupportRequestStatuses
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.cs.repo.SupportRequestRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class SupportRequestAggHandler(
    private val repo: SupportRequestRepository,
) {
    /** 创建工单：status 固定 OPEN，各时间戳 null；身份/installId/locale 由 opCtx 落库。 */
    fun create(mc: ModuleCtx, req: CreateSupportRequestReq): UUID {
        val op = mc.op
        val id = UuidV7.generate()
        val now = Instant.now()
        val entity = SupportRequest {
            this.id = id
            this.projectId = op.mustGetProjectId()
            this.installId = op.installId
            this.customerId = op.actorId
            this.locale = op.locale
            this.country = op.country
            this.currency = op.currency
            this.title = req.title
            this.message = req.message
            this.email = req.email
            this.phone = req.phone
            this.category = req.category
            this.status = SupportRequestStatuses.OPEN
            this.attachments = req.attachments
            this.createdAt = now
            this.updatedAt = now
            this.firstRepliedAt = null
            this.lastAgentRepliedAt = null
            this.lastCustomerRepliedAt = null
            this.resolvedAt = null
            this.closedAt = null
        }
        repo.save(mc, entity)
        return id
    }

    /** 我的工单详情（owner-scoped，需登录）。 */
    fun findMineById(mc: ModuleCtx, id: UUID): SupportRequest {
        val projectId = mc.op.mustGetProjectId()
        val customerId = mc.op.mustGetActorId()
        return repo.findByIdOwned(mc, projectId, customerId, id)
            ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    /** 我的工单列表（owner-scoped，需登录）。 */
    fun findMine(mc: ModuleCtx, req: ListSupportRequestsReq?): Page<SupportRequest> {
        val projectId = mc.op.mustGetProjectId()
        val customerId = mc.op.mustGetActorId()
        val limit = (req?.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val cursor = req?.cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return repo.findMineByCursor(mc, projectId, customerId, limit, cursor)
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
    }
}
