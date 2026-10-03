package com.ifmix.core.api.dto.notification

data class NotificationContent(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val link: String,
)
