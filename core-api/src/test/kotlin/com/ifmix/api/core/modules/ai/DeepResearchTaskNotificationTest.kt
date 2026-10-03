package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotiType
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.notification.NotificationFacade
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

class DeepResearchTaskNotificationTest {
    private val notificationFacade = mock<NotificationFacade>()
    private val scanHandler = mock<ScanAggHandler>()
    private val service = DeepResearchTaskService(
        mcFactory = mock<ModuleCtxFactory>(),
        txRunner = mock<TxRunner>(),
        scanRunner = mock(),
        scanAggHandler = scanHandler,
        notificationFacade = notificationFacade,
        executor = Executor { },
    )
    private val installId = UUID.randomUUID()
    private val ctx = DeepResearchTaskContext(
        projectId = "test-app",
        customerId = UUID.randomUUID(),
        deepResearchId = UUID.randomUUID(),
        scanRecordId = UUID.randomUUID(),
        images = emptyList(),
        locale = "en",
        country = "US",
        currency = "USD",
        promptVersion = "v10",
        createdAt = Instant.now(),
        installId = installId,
    )
    private val result = DeepResearchResult(
        scanRecordId = ctx.scanRecordId,
        projectId = ctx.projectId,
        basicResult = emptyMap(),
        premiumResult = emptyMap(),
        promptVersion = "v10",
    )
    private val content = NotificationContent("title", "body", link = "/p/test-app/scan-result/${ctx.scanRecordId}")

    @Test
    fun `only finalized latest task sends deep research notification`() {
        whenever(scanHandler.buildDeepResearchNotificationContent(ctx, result)).thenReturn(content)

        service.dispatchSuccessNotification(ctx, result, finalized = false)
        verifyNoInteractions(notificationFacade)

        service.dispatchSuccessNotification(ctx, result, finalized = true)
        val request = argumentCaptor<NotificationRequest>()
        verify(notificationFacade).sendToInstall(request.capture())
        assertThat(request.firstValue.installId).isEqualTo(installId)
        assertThat(request.firstValue.notiType).isEqualTo(NotiType.DEEP_RESEARCH)
    }

    @Test
    fun `missing install skips and notification failure is swallowed`() {
        service.dispatchSuccessNotification(ctx.copy(installId = null), result, finalized = true)
        verifyNoInteractions(notificationFacade)

        whenever(scanHandler.buildDeepResearchNotificationContent(ctx, result)).thenReturn(content)
        doThrow(IllegalStateException("push failed")).whenever(notificationFacade).sendToInstall(any())
        service.dispatchSuccessNotification(ctx, result, finalized = true)
    }
}
