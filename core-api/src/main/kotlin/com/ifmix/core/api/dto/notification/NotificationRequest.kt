package com.ifmix.core.api.dto.notification

import java.util.UUID

data class NotificationRequest(
    val projectId: String,
    val installId: UUID,
    val content: NotificationContent,
    val notiType: NotiType = NotiType.SCAN_RESULT,
)
