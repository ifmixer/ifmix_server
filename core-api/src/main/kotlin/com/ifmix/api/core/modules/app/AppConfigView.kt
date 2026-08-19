package com.ifmix.api.core.modules.app

import com.ifmix.api.core.modules.app.entity.AppleConfig
import com.ifmix.api.core.modules.app.entity.GoogleClientIds
import com.ifmix.api.core.modules.app.entity.GoogleConfig
import com.ifmix.api.core.modules.app.entity.IapConfig

/**
 * 配置视图（BO）：保持与 AppConfigEntity 相同的嵌套结构输出，
 * 供下游（iap/antique 的验证器、tier 解析等）及 API 响应直接使用。
 */
data class AppConfigView(
    val id: String,
    val appId: String,
    val authTenantId: String?,
    val revision: Int,
    val appleBundleId: String?,
    val androidPackageName: String?,
    val apple: AppleConfig,
    val google: GoogleConfig,
    val iap: IapConfig,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)

/** 写入 app_config 新版本用的分组 patch（缺省字段沿用当前版本）。 */
data class AppConfigPatch(
    val authTenantId: String? = null,
    val appleBundleId: String? = null,
    val androidPackageName: String? = null,
    val apple: AppleConfig? = null,
    val google: GoogleConfig? = null,
    val iap: IapConfig? = null,
)
