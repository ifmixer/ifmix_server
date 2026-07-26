package com.ifmix.api.core.modules.appconfig

/** AppConfigDocument（内嵌结构）→ AppConfig（扁平视图）。iapEnv 缺省 "production"。 */
object AppConfigMapper {

    fun toFlat(doc: AppConfigDocument): AppConfig = AppConfig(
        id = doc.id,
        appId = doc.appId,
        authTenantId = doc.authTenantId,
        configVersion = doc.configVersion,
        appleBundleId = doc.appleBundleId,
        androidPackageName = doc.androidPackageName,
        appleAppAppleId = doc.apple.appAppleId,
        appleIssuerId = doc.apple.issuerId,
        appleKeyId = doc.apple.keyId,
        applePrivateKey = doc.apple.privateKey,
        appleServicesId = doc.apple.servicesId,
        googleServiceAccount = doc.google.serviceAccount,
        googleClientIds = doc.google.clientIds,
        productTierMap = doc.iap.productTierMap,
        iapEnv = doc.iap.env ?: "production",
        createdAt = doc.createdAt,
        updatedAt = doc.updatedAt,
    )
}
