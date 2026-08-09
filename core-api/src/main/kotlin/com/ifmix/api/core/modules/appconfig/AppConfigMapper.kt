package com.ifmix.api.core.modules.appconfig

/** AppConfigDocument → AppConfig（保持嵌套结构不变）。 */
object AppConfigMapper {

    fun toView(doc: AppConfigDocument): AppConfigView = AppConfigView(
        id = doc.id,
        appId = doc.appId,
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
