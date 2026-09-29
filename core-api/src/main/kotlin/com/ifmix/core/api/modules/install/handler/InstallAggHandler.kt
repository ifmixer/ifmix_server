package com.ifmix.core.api.modules.install.handler

import com.ifmix.core.api.entity.install.Install
import com.ifmix.core.api.entity.install.InstallCustomerRelation
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.install.repo.InstallCustomerRelationRepository
import com.ifmix.core.api.modules.install.repo.InstallRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class InstallAggHandler(
    private val installRepo: InstallRepository,
    private val relationRepo: InstallCustomerRelationRepository,
    private val jwt: AuthJwtService,
) {

    data class CreateInstallRes(val installId: UUID, val installToken: String)

    /** 生成 installId + 写 core_install + 签发 installToken(type=5)。header 字段从 mc.action 取；reg_ip=clientIp。 */
    fun createInstall(mc: ModuleCtx, deviceInfo: Map<String, Any?>?): CreateInstallRes {
        val projectId = mc.projectId!!
        val installId = UuidV7.generate()
        val now = Instant.now()
        installRepo.save(mc, Install {
            this.id = UuidV7.generate()
            this.projectId = projectId
            this.installId = installId
            this.platform = platformInt(mc.action.clientPlatform)
            this.deviceInfo = deviceInfo
            this.appVersion = mc.action.appVersion
            this.otaVersion = mc.action.otaVersion
            this.locale = mc.action.locale
            this.country = mc.action.country
            this.currency = mc.action.currency
            this.regIp = mc.action.clientIp
            this.firebaseInstallId = null
            this.fcmToken = null
            this.createdAt = now
            this.updatedAt = now
        })
        val token = jwt.signInstall(installId.toString(), projectId)
        return CreateInstallRes(installId = installId, installToken = token)
    }

    /**
     * 按 (projectId, installId) 更新，仅覆盖非空字段。installId 来自 token iid（调用方传入）。
     * reg_ip write-once：不更新，保留注册时值。
     */
    fun updateInstall(
        mc: ModuleCtx, installId: UUID,
        firebaseInstallId: String?, fcmToken: String?, deviceInfo: Map<String, Any?>?,
    ): Boolean {
        val projectId = mc.projectId!!
        val existing = installRepo.findByInstallId(mc, projectId, installId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "install not found")
        installRepo.save(mc, Install {
            this.id = existing.id
            this.projectId = projectId
            this.installId = installId
            this.platform = platformInt(mc.action.clientPlatform) ?: existing.platform
            this.deviceInfo = deviceInfo ?: existing.deviceInfo
            this.appVersion = mc.action.appVersion ?: existing.appVersion
            this.otaVersion = mc.action.otaVersion ?: existing.otaVersion
            this.locale = mc.action.locale ?: existing.locale
            this.country = mc.action.country ?: existing.country
            this.currency = mc.action.currency ?: existing.currency
            this.regIp = existing.regIp // write-once：updateInstall 不改
            this.firebaseInstallId = firebaseInstallId ?: existing.firebaseInstallId
            this.fcmToken = fcmToken ?: existing.fcmToken
            this.createdAt = existing.createdAt
            this.updatedAt = Instant.now()
        })
        return true
    }

    /** 绑定 install↔customer：换绑（软删该 install 其它有效关系）+ upsert（插/复活/不动）。幂等。 */
    fun bind(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID) {
        relationRepo.softDeleteOtherActiveByInstall(mc, projectId, installId, keepCustomerId = customerId)
        val existing = relationRepo.findAnyByPair(mc, projectId, installId, customerId)
        when (val action = decideBindAction(existing?.id, existing?.deletedAt != null)) {
            BindAction.Insert -> relationRepo.save(mc, InstallCustomerRelation {
                this.id = UuidV7.generate()
                this.projectId = projectId
                this.installId = installId
                this.customerId = customerId
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            })
            is BindAction.Reactivate -> relationRepo.reactivate(mc, action.id)
            BindAction.NoOp -> Unit
        }
    }

    /** 解绑（logout）：软删有效关系。 */
    fun unbind(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID) {
        relationRepo.softDeleteActive(mc, projectId, installId, customerId)
    }

    /** 删除账号：软删该 customer 全部有效关系。 */
    fun unbindAllForCustomer(mc: ModuleCtx, projectId: String, customerId: UUID) {
        relationRepo.softDeleteAllActiveByCustomer(mc, projectId, customerId)
    }

    private fun platformInt(p: ClientPlatform?): Int? = when (p) {
        ClientPlatform.ANDROID -> 10
        ClientPlatform.IOS -> 20
        ClientPlatform.WEB -> 30
        null -> null
    }

    companion object {
        sealed interface BindAction {
            /** 无任何行 → 插新行。 */
            data object Insert : BindAction
            /** 已有有效行 → 幂等不动。 */
            data object NoOp : BindAction
            /** 有软删行 → 复活。 */
            data class Reactivate(val id: UUID) : BindAction
        }

        /**
         * 绑定判定（纯函数）：按 (install, customer) 的既有行状态决定 插/复活/不动。
         * @param existingId 既有行 id（含软删查得），null=无行
         * @param existingDeleted 既有行是否已软删
         */
        fun decideBindAction(existingId: UUID?, existingDeleted: Boolean): BindAction = when {
            existingId == null -> BindAction.Insert
            existingDeleted -> BindAction.Reactivate(existingId)
            else -> BindAction.NoOp
        }
    }
}
