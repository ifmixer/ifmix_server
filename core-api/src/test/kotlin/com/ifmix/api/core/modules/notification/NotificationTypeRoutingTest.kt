package com.ifmix.core.api.modules.notification

import assertk.assertThat
import assertk.assertions.isNull
import com.ifmix.core.api.dto.notification.NotiType
import assertk.assertions.isNotNull
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.auth.install.InstallFacade
import com.ifmix.core.api.modules.auth.install.InstallNotificationTarget
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class NotificationTypeRoutingTest {
    private val installFacade = mock<InstallFacade>()
    private val handler = NotificationHandler(installFacade)
    private val projectId = "test-app"
    private val installId = UUID.randomUUID()
    private val ctx = ActionContext(projectId = projectId)
    private val content = NotificationContent("title", "body", link = "/p/$projectId/scan-result/${UUID.randomUUID()}")

    @Test
    fun `deep research disabled does not disable scan result notification`() {
        whenever(installFacade.findNotificationTarget(any(), eq(installId))).thenReturn(
            InstallNotificationTarget(
                enabled = true,
                fcmToken = "token",
                fcmTokenValid = true,
                deepResearchNotiEnabled = false,
            )
        )

        val deep = handler.resolve(ctx, NotificationRequest(projectId, installId, content, notiType = NotiType.DEEP_RESEARCH))
        val scan = handler.resolve(ctx, NotificationRequest(projectId, installId, content, notiType = NotiType.SCAN_RESULT))

        assertThat(deep).isNull()
        assertThat(scan).isNotNull()
    }
}
