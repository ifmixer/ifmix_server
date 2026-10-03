package com.ifmix.core.api.modules.notification.channel

import com.ifmix.core.api.dto.notification.NotificationContent

enum class PushDestinationKind { TOKEN, TOPIC }

data class PushDestination(
    val kind: PushDestinationKind,
    val value: String,
)

data class PushSendResult(
    val permanentTokenFailure: Boolean = false,
    val errorCode: String? = null,
)

interface PushChannel {
    fun send(destination: PushDestination, content: NotificationContent): PushSendResult
}
