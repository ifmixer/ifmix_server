package com.ifmix.core.api.modules.notification

import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.install.InstallFacade
import com.ifmix.core.api.modules.notification.channel.PushChannel
import com.ifmix.core.api.modules.notification.channel.PushDestinationKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NotificationDispatchService(
    private val handler: NotificationHandler,
    private val channel: PushChannel,
    private val installFacade: InstallFacade,
    private val mcFactory: ModuleCtxFactory,
    private val txRunner: TxRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 外部 push 永远在事务外；仅 token 永久失效时另开短事务更新 install。 */
    fun sendToInstall(request: NotificationRequest) {
        val actionCtx = ActionContext(projectId = request.projectId, isMutation = true, preferReader = false)
        val destination = handler.resolve(actionCtx, request)
        if (destination == null) {
            log.debug("Notification not sent (resolve returned null). projectId={}, installId={}", request.projectId, request.installId)
            return
        }
        val result = try {
            channel.send(request.projectId, destination, request.content)
        } catch (t: Throwable) {
            log.warn(
                "Notification push failed. projectId={}, installId={}, channel=push, destinationKind={}, destination={}",
                request.projectId, request.installId, destination.kind.name.lowercase(), destination.masked(), t,
            )
            return
        }
        if (result.skipped) {
            log.debug("Notification push skipped (project not FCM-configured). projectId={}, installId={}", request.projectId, request.installId)
            return
        }
        if (result.errorCode == null) {
            log.info(
                "Notification push sent. projectId={}, installId={}, destinationKind={}, destination={}",
                request.projectId, request.installId, destination.kind.name.lowercase(), destination.masked(),
            )
            return
        }
        if (result.permanentTokenFailure && destination.kind == PushDestinationKind.TOKEN) {
            log.warn(
                "FCM token invalid, marking fcm_token_valid=false. projectId={}, installId={}, destination={}, fcmErrorCode={}",
                request.projectId, request.installId, destination.masked(), result.errorCode,
            )
            val mutationCtx = actionCtx.copy(isMutation = true, preferReader = false)
            txRunner.withTx(mcFactory.forProject(mutationCtx)) { txCtx ->
                installFacade.invalidateFcmToken(txCtx.action, request.installId, destination.value)
            }
        } else {
            log.warn(
                "Notification push rejected. projectId={}, installId={}, channel=push, destinationKind={}, destination={}, fcmErrorCode={}",
                request.projectId, request.installId, destination.kind.name.lowercase(), destination.masked(), result.errorCode,
            )
        }
    }
}

@Service
class NotificationFacade(
    private val dispatch: NotificationDispatchService,
) {
    fun sendToInstall(request: NotificationRequest) = dispatch.sendToInstall(request)
}
