package com.ifmix.core.api.modules.notification

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.modules.auth.install.InstallNotificationTarget
import com.ifmix.core.api.modules.notification.channel.PushDestinationKind
import assertk.assertions.isNull
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.auth.install.InstallFacade
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class NotificationHandlerTest {
    private val installFacade = mock<InstallFacade>()
    private val handler = NotificationHandler(installFacade)
    private val projectId = "test-app"
    private val installId = UUID.randomUUID()
    private val ctx = ActionContext(projectId = projectId)
    private val request = NotificationRequest(
        projectId = projectId,
        installId = installId,
        content = NotificationContent(
            title = "Scan complete",
            body = "Open result",
            imageUrl = "https://cdn/main.jpg",
            link = "/p/$projectId/scan-result/${UUID.randomUUID()}",
        ),
    )

    @Test
    fun `disabled install skips without resolving destination`() {
        whenever(installFacade.findNotificationTarget(any(), eq(installId)))
            .thenReturn(InstallNotificationTarget(enabled = false, fcmToken = "secret", fcmTokenValid = true))

        assertThat(handler.resolve(ctx, request)).isNull()
        verify(installFacade).findNotificationTarget(any(), eq(installId))
        verifyNoMoreInteractions(installFacade)
    }

    @Test
    fun `valid token selects direct destination`() {
        whenever(installFacade.findNotificationTarget(any(), eq(installId)))
            .thenReturn(InstallNotificationTarget(enabled = true, fcmToken = "token", fcmTokenValid = true))

        val destination = handler.resolve(ctx, request)

        assertThat(destination?.kind).isEqualTo(PushDestinationKind.TOKEN)
        assertThat(destination?.value).isEqualTo("token")
    }

    @Test
    fun `missing or invalid token selects install topic`() {
        whenever(installFacade.findNotificationTarget(any(), eq(installId)))
            .thenReturn(InstallNotificationTarget(enabled = true, fcmToken = null, fcmTokenValid = true))
        assertThat(handler.resolve(ctx, request)?.value).isEqualTo("install_$installId")

        whenever(installFacade.findNotificationTarget(any(), eq(installId)))
            .thenReturn(InstallNotificationTarget(enabled = true, fcmToken = "old", fcmTokenValid = false))
        assertThat(handler.resolve(ctx, request)?.value).isEqualTo("install_$installId")
    }
}
