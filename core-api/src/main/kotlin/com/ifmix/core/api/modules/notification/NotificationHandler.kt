package com.ifmix.core.api.modules.notification

import com.ifmix.core.api.dto.notification.NotiType
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.auth.install.InstallFacade
import com.ifmix.core.api.modules.notification.channel.PushDestination
import com.ifmix.core.api.modules.notification.channel.PushDestinationKind
import org.springframework.stereotype.Component

@Component
class NotificationHandler(
    private val installFacade: InstallFacade,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun resolve(ctx: ActionContext, request: NotificationRequest): PushDestination? {
        val target = installFacade.findNotificationTarget(ctx.copy(projectId = request.projectId), request.installId)
        if (target == null) {
            log.debug("Notification skipped: install not found. installId={}, notiType={}", request.installId, request.notiType)
            return null
        }
        val enabled = when (request.notiType) {
            NotiType.SCAN_RESULT -> target.enabled
            NotiType.DEEP_RESEARCH -> target.deepResearchNotiEnabled
        }
        if (!enabled) {
            log.debug("Notification skipped: user switch off. installId={}, notiType={}", request.installId, request.notiType)
            return null
        }
        val dest = if (!target.fcmToken.isNullOrBlank() && target.fcmTokenValid) {
            PushDestination(PushDestinationKind.TOKEN, target.fcmToken)
        } else {
            PushDestination(PushDestinationKind.TOPIC, "install_${request.installId}")
        }
        log.debug(
            "Notification resolved. installId={}, notiType={}, destinationKind={}, hasToken={}, tokenValid={}",
            request.installId, request.notiType, dest.kind.name.lowercase(),
            !target.fcmToken.isNullOrBlank(), target.fcmTokenValid,
        )
        return dest
    }
}
