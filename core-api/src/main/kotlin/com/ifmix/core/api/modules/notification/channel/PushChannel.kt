package com.ifmix.core.api.modules.notification.channel

import com.ifmix.core.api.dto.notification.NotificationContent

enum class PushDestinationKind { TOKEN, TOPIC }

data class PushDestination(
    val kind: PushDestinationKind,
    val value: String,
) {
    /**
     * 日志安全展示：TOPIC 名不敏感全显；TOKEN 是凭据，脱敏为「前8…后4(len=N)」，
     * 既能定位/区分是哪个 token，又不泄露完整凭据（设计 §5.2 禁记完整 token）。
     */
    fun masked(): String = when (kind) {
        PushDestinationKind.TOPIC -> value
        PushDestinationKind.TOKEN ->
            if (value.length <= 12) "***(len=${value.length})"
            else "${value.take(8)}…${value.takeLast(4)}(len=${value.length})"
    }
}

data class PushSendResult(
    val skipped: Boolean = false,
    val permanentTokenFailure: Boolean = false,
    val errorCode: String? = null,
)

interface PushChannel {
    fun send(projectId: String, installId: java.util.UUID, destination: PushDestination, content: NotificationContent): PushSendResult
}
