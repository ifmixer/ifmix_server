package com.ifmix.core.api.modules.notification

import com.ifmix.core.api.dto.notification.NotiType
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.install.InstallFacade
import com.ifmix.core.api.modules.notification.channel.PushDestination
import com.ifmix.core.api.modules.notification.channel.PushDestinationKind
import org.springframework.stereotype.Component

@Component
class NotificationHandler(
    private val installFacade: InstallFacade,
) {
    fun resolve(ctx: ActionContext, request: NotificationRequest): PushDestination? {
        val target = installFacade.findNotificationTarget(ctx.copy(projectId = request.projectId), request.installId)
            ?: return null
        val enabled = when (request.notiType) {
            NotiType.SCAN_RESULT -> target.enabled
            NotiType.DEEP_RESEARCH -> target.deepResearchNotiEnabled
        }
        if (!enabled) return null
        return if (!target.fcmToken.isNullOrBlank() && target.fcmTokenValid) {
            PushDestination(PushDestinationKind.TOKEN, target.fcmToken)
        } else {
            PushDestination(PushDestinationKind.TOPIC, "install_${request.installId}")
        }
    }
}
