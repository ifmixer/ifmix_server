package com.ifmix.core.api.modules.notification.channel

import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.infra.push.FirebaseAppRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * FCM 推送渠道。FirebaseMessaging 按 projectId 从 [FirebaseAppRegistry] 取（凭据项目级，见设计）。
 * project 无 FCM 配置 → registry 返回 null → 本次跳过（noop 效果 + 日志），不报错。
 */
@Component
class FcmPushChannel(
    private val registry: FirebaseAppRegistry,
) : PushChannel {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(projectId: String, destination: PushDestination, content: NotificationContent): PushSendResult {
        log.debug("FCM resolving messaging. projectId={}", projectId)
        val messaging = registry.getMessaging(projectId)
        if (messaging == null) {
            // project 未配置 FCM：等价 noop（是否发由 feature flag 控制；能否发由有无凭据决定）
            log.info("FCM skipped: no messaging for project (not configured). projectId={}", projectId)
            return PushSendResult(skipped = true)
        }
        val notification = Notification.builder()
            .setTitle(content.title)
            .setBody(content.body)
            .apply { content.imageUrl?.let { setImage(it) } }
            .build()
        val builder = Message.builder()
            .setNotification(notification)
            .putData("link", content.link)
        when (destination.kind) {
            PushDestinationKind.TOKEN -> builder.setToken(destination.value)
            PushDestinationKind.TOPIC -> builder.setTopic(destination.value)
        }
        return try {
            log.debug("FCM sending. projectId={}, destinationKind={}", projectId, destination.kind.name.lowercase())
            val messageId = messaging.send(builder.build())
            log.debug("FCM send returned. projectId={}, messageId={}", projectId, messageId)
            PushSendResult()
        } catch (e: FirebaseMessagingException) {
            log.warn(
                "FCM send rejected. projectId={}, destinationKind={}, errorCode={}, messagingErrorCode={}, message={}",
                projectId,
                destination.kind.name.lowercase(),
                e.errorCode,
                e.messagingErrorCode,
                e.message,
                e,
            )
            PushSendResult(
                permanentTokenFailure = destination.kind == PushDestinationKind.TOKEN &&
                    e.messagingErrorCode == MessagingErrorCode.UNREGISTERED,
                errorCode = e.messagingErrorCode?.name ?: e.errorCode.toString(),
            )
        }
    }
}
