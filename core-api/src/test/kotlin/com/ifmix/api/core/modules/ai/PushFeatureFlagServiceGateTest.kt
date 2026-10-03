package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.notification.NotificationFacade
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

class PushFeatureFlagServiceGateTest {
    private val notificationFacade = mock<NotificationFacade>()
    private val scanHandler = mock<ScanAggHandler>()
    private val service = ScanTaskService(
        mcFactory = mock<ModuleCtxFactory>(),
        txRunner = mock<TxRunner>(),
        scanRunner = mock(),
        scanAggHandler = scanHandler,
        notificationFacade = notificationFacade,
        executor = Executor { },
    )
    private val context = ScanTaskContext(
        projectId = "test-app",
        customerId = UUID.randomUUID(),
        installId = UUID.randomUUID(),
        scanId = UUID.randomUUID(),
        locale = "en",
        country = "US",
        currency = "USD",
        images = emptyList(),
        collected = false,
        promptVersion = "v10",
        createdAt = Instant.now(),
        scanResultPushEnabled = true,
    )
    private val request = NotificationRequest(
        projectId = context.projectId,
        installId = context.installId!!,
        content = NotificationContent("title", "body", link = "/p/test-app/scan-result/${context.scanId}"),
    )

    @Test
    fun `scan flag off skips notification and flag on sends`() {
        service.dispatchSuccessNotification(context.copy(scanResultPushEnabled = false), emptyMap(), finalized = true)
        verifyNoInteractions(notificationFacade)

        whenever(scanHandler.buildScanNotificationRequest(context, emptyMap())).thenReturn(request)
        service.dispatchSuccessNotification(context, emptyMap(), finalized = true)

        val captured = argumentCaptor<NotificationRequest>()
        verify(notificationFacade).sendToInstall(captured.capture())
        assertThat(captured.firstValue.installId).isEqualTo(context.installId)
    }
}
