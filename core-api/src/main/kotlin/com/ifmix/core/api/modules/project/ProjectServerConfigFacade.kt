package com.ifmix.core.api.modules.project

import com.ifmix.core.api.infra.attest.AttestConfig
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.project.repo.ProjectServerConfigRepository
import org.springframework.stereotype.Service
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端专属项目配置 facade（仅后端内部用，不暴露 customer GraphQL）。
 */
@Service
class ProjectServerConfigFacade(
    private val mcFactory: ModuleCtxFactory,
    private val repo: ProjectServerConfigRepository,
) {
    /** 读某 project 的 FCM service account 配置；无则 null（该 project 不发 FCM push）。 */
    fun findFcmConfig(projectId: String): Map<String, Any?>? {
        val mc = mcFactory.default(ActionContext(projectId = projectId))
        return repo.findByProjectId(mc, projectId)?.fcmConfig
    }

    /**
     * 读某 project 的 App Attest 配置（解析后的 [AttestConfig]；null = 未配置，§4.1「null = 关」）。
     *
     * 进程内缓存：ConcurrentHashMap + Optional null 哨兵（参照 FirebaseAppRegistry），
     * **重启生效**——createInstall / createAttestChallenge 是无鉴权高频入口，不允许每请求查库。
     */
    private val attestConfigCache = ConcurrentHashMap<String, Optional<AttestConfig>>()

    fun findAttestConfig(projectId: String): AttestConfig? =
        attestConfigCache.computeIfAbsent(projectId) { pid ->
            val mc = mcFactory.default(ActionContext(projectId = pid))
            val raw = repo.findByProjectId(mc, pid)?.appAttestConfig
            Optional.ofNullable(AttestConfig.parse(raw))
        }.orElse(null)

    /** 测试入口：清缓存（模拟重启/配置变更）。 */
    fun evictAttestConfigCache() {
        attestConfigCache.clear()
    }
}
