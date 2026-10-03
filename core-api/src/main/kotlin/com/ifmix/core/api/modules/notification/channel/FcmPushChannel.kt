package com.ifmix.core.api.modules.notification.channel

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import com.ifmix.core.api.dto.notification.NotificationContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.noti.fcm.enabled"], havingValue = "true")
class FcmPushChannel(
    private val messaging: FirebaseMessaging,
) : PushChannel {
    override fun send(destination: PushDestination, content: NotificationContent): PushSendResult {
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
            messaging.send(builder.build())
            PushSendResult()
        } catch (e: FirebaseMessagingException) {
            PushSendResult(
                permanentTokenFailure = destination.kind == PushDestinationKind.TOKEN &&
                    e.messagingErrorCode == MessagingErrorCode.UNREGISTERED,
                errorCode = e.messagingErrorCode?.name ?: e.errorCode.toString(),
            )
        }
    }
}

@Component
@ConditionalOnProperty(name = ["app.noti.fcm.enabled"], havingValue = "false", matchIfMissing = true)
class NoopPushChannel : PushChannel {
    override fun send(destination: PushDestination, content: NotificationContent): PushSendResult = PushSendResult()
}
