package com.ifmix.api.core.modules.auth.handler

import com.ifmix.api.core.common.http.OperationContext
import com.ifmix.api.core.modules.auth.UpsertInput
import com.ifmix.api.core.modules.auth.repo.AuthProviderIdentityRepo
import com.ifmix.api.core.modules.auth.repo.AuthDeviceSecretRepo
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * 处理用户身份和设备的 CRUD 操作。
 */
@Component
class AuthEntityHandler(
    private val providerIdentityRepo: AuthProviderIdentityRepo,
    private val deviceSecretRepo: AuthDeviceSecretRepo,
) {
    /**
     * 为用户签发新的设备密钥。
     * @return Pair of (deviceId hex string, plain secret)
     */
    fun issueDeviceSecret(opCtx: OperationContext, tenantId: String, identityId: ObjectId): Pair<String, String> =
        deviceSecretRepo.issue(tenantId, identityId, opCtx.installId)

    /**
     * 通过提供商标识 upsert 用户身份。
     * @return authIdentityId as ObjectId
     */
    fun upsertProviderIdentity(tenantId: String, input: UpsertInput): ObjectId =
        ObjectId(providerIdentityRepo.upsert(tenantId, input))

    /**
     * 查找有效的设备密钥。
     */
    fun findValidDeviceSecret(tenantId: String, secretPlain: String) =
        deviceSecretRepo.findValid(tenantId, secretPlain)
}
