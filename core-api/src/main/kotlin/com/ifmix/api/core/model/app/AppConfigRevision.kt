package com.ifmix.api.core.model.app

import com.ifmix.api.core.model.appconfig.AppleConfigValue
import com.ifmix.api.core.model.appconfig.ConfigContent
import com.ifmix.api.core.model.appconfig.GoogleClientIdsValue
import com.ifmix.api.core.model.appconfig.GoogleConfigValue
import com.ifmix.api.core.model.appconfig.IapConfigValue
import com.ifmix.api.core.model.appconfig.WechatConfigValue
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * App 配置版本领域模型。
 * content 字段为 JSON 字符串，对应 DB JSONB 列。
 */
data class AppConfigRevision(
    val id: UUID,
    val appId: UUID,
    val authTenantId: UUID? = null,
    val appleBundleId: String? = null,
    val androidPackageName: String? = null,
    val revisionNumber: Int,
    val createdAt: Instant,
    val enabled: Boolean,
    val slug: String,
    val content: String,  // JSON 字符串，对应 DB JSONB 列
    val note: String,
) {
    companion object {
        private val mapper = jacksonObjectMapper()
        private val emptyConfig = ConfigContent()

        /** 将 content JSON 字符串解析为 ConfigContent（含默认值回退） */
        fun parseContent(content: String): ConfigContent =
            runCatching { mapper.readValue(content, ConfigContent::class.java) }.getOrElse { emptyConfig }
    }

    /** 解析后的配置内容，返回空对象以避免 NPE */
    val contentConfig: ConfigContent get() = parseContent(content)
}
