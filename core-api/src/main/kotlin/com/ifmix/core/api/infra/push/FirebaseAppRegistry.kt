package com.ifmix.core.api.infra.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.ifmix.core.api.modules.project.ProjectServerConfigFacade
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 projectId 懒加载 + 缓存 FirebaseMessaging（设计：FCM 凭据项目级，存 core_project_server_config.fcm_config）。
 *
 * - 首次请求某 project → 读 server config 的 fcm_config（service account JSON）→ initializeApp(name=projectId) → 缓存；
 * - project 无 fcm_config → 返回 null（该 project 不发 FCM push，调用方走 noop 效果）；
 * - 缓存失效策略：**重启生效**（凭据变更后需重启；本期不做热更新，ponytail: 低频运维操作）。
 */
@Component
class FirebaseAppRegistry(
    private val serverConfigFacade: ProjectServerConfigFacade,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** null 哨兵：缓存"该 project 无有效 FCM 配置"的结论，避免每次 push 都查库。 */
    private val cache = ConcurrentHashMap<String, java.util.Optional<FirebaseMessaging>>()

    /** 取某 project 的 FirebaseMessaging；无配置/初始化失败返回 null。 */
    fun getMessaging(projectId: String): FirebaseMessaging? =
        cache.computeIfAbsent(projectId) { pid ->
            java.util.Optional.ofNullable(init(pid))
        }.orElse(null)

    private fun init(projectId: String): FirebaseMessaging? {
        return try {
            val fcmConfig = serverConfigFacade.findFcmConfig(projectId)
            if (fcmConfig.isNullOrEmpty()) {
                log.info("No FCM config for project; push disabled. projectId={}", projectId)
                return null
            }
            val json = objectMapper.writeValueAsBytes(fcmConfig)
            val appName = "fcm-$projectId"
            val app = FirebaseApp.getApps().firstOrNull { it.name == appName }
                ?: FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(ByteArrayInputStream(json)))
                        .build(),
                    appName,
                )
            FirebaseMessaging.getInstance(app)
        } catch (e: Exception) {
            log.error("Failed to init FirebaseApp (config read or SDK init). projectId={}", projectId, e)
            null
        }
    }
}
