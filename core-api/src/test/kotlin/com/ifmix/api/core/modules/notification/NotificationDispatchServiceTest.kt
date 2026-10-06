package com.ifmix.core.api.modules.notification

import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.modules.auth.install.InstallFacade
import com.ifmix.core.api.modules.notification.channel.PushChannel
import com.ifmix.core.api.modules.notification.channel.PushDestination
import com.ifmix.core.api.modules.notification.channel.PushDestinationKind
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import com.ifmix.core.api.infra.tx.TxRunner
import java.util.UUID

class NotificationDispatchServiceTest {
    @Test
    fun `push channel exception is swallowed after scan commit`() {
        val handler = mock<NotificationHandler>()
        val channel = mock<PushChannel>()
        val installFacade = mock<InstallFacade>()
        val mcFactory = mock<ModuleCtxFactory>()
        val txRunner = mock<TxRunner>()
        val service = NotificationDispatchService(handler, channel, installFacade, mcFactory, txRunner)
        val request = NotificationRequest(
            projectId = "test-app",
            installId = UUID.randomUUID(),
            content = NotificationContent("title", "body", link = "/p/test-app/scan-result/${UUID.randomUUID()}"),
        )
        whenever(handler.resolve(any(), any())).thenReturn(PushDestination(PushDestinationKind.TOKEN, "token"))
        doThrow(IllegalStateException("FCM down")).whenever(channel).send(any(), any(), any(), any())

        service.sendToInstall(request)
    }
}
