package com.ifmix.api.core.modules.appconfig

/** AppConfigEntity → AppConfig（保持嵌套结构不变）。 */
object AppConfigMapper {

    fun toView(doc: AppConfigEntity): AppConfigView = AppConfigView(
        id = doc.id.toHexString(),
        appId = doc.appId.toHexString(),
        authTenantId = doc.authTenantId,
        revision = doc.revision,
        appleBundleId = doc.appleBundleId,
        androidPackageName = doc.androidPackageName,
        apple = doc.apple,
        google = doc.google,
        iap = doc.iap,
        createdAt = doc.createdAt,
        updatedAt = doc.updatedAt,
    )
}
