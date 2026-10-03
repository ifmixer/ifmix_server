package com.ifmix.core.api.modules.notification

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import com.ifmix.core.api.dto.notification.NotificationContent
import com.ifmix.core.api.dto.notification.NotificationRequest
import com.ifmix.core.api.dto.notification.NotiType
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.notification.channel.FcmPushChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Explicitly opt-in local smoke test. It sends one real FCM notification using the same
 * NotificationHandler + FcmPushChannel path as ScanTaskService.
 *
 * Example:
 * RUN_FCM_LIVE_TEST=true FCM_TEST_INSTALL_ID=<uuid> \
 * JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8118 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8118' \
 * ./gradlew :core-api:test --tests 'com.ifmix.core.api.modules.notification.FcmLiveSmokeTest'
 */
@EnabledIfEnvironmentVariable(named = "RUN_FCM_LIVE_TEST", matches = "true")
@SpringBootTest
@ActiveProfiles("local")
class FcmLiveSmokeTest(
    @Autowired private val handler: NotificationHandler,
    @Autowired private val channel: FcmPushChannel,
) {
    @Test
    fun `sends a real FCM v1 notification through the current Kotlin channel`() {
        val installId = UUID.fromString(requireNotNull(System.getenv("FCM_TEST_INSTALL_ID")) {
            "FCM_TEST_INSTALL_ID is required when RUN_FCM_LIVE_TEST=true"
        })
        val projectId = System.getenv("FCM_TEST_PROJECT_ID") ?: "antique"
        val request = NotificationRequest(
            projectId = projectId,
            installId = installId,
            notiType = NotiType.SCAN_RESULT,
            content = NotificationContent(
                title = "Antique FCM Kotlin smoke test",
                body = "Sent by FcmPushChannel through the configured JVM proxy.",
                link = "/p/$projectId/scan-result/${UUID.randomUUID()}",
            ),
        )
        val context = ActionContext(projectId = projectId, isMutation = true, preferReader = false)
        val destination = requireNotNull(handler.resolve(context, request)) {
            "install has no enabled FCM notification destination"
        }

        val result = channel.send(projectId, request.installId, destination, request.content)

        assertThat(result.skipped).isFalse()
        assertThat(result.errorCode).isNull()
    }
}
