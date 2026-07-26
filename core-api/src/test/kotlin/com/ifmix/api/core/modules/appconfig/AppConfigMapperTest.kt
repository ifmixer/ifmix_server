package com.ifmix.api.core.modules.appconfig

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AppConfigMapperTest {

    @Test
    fun flattensNestedConfigWithDefaults() {
        val doc = AppConfigDocument().apply {
            id = "aaaaaaaaaaaaaaaaaaaaaaaa"
            appId = "app-1"
            appleBundleId = "com.example.app"
            apple = AppleConfig(issuerId = "iss-1", privateKey = "pk-1")
            google = GoogleConfig(clientIds = GoogleClientIds(ios = "ios-client"))
            iap = IapConfig(productTierMap = mapOf("prod.pro" to "pro")) // env null → default
            configVersion = 3
            createdAt = Instant.ofEpochMilli(1000)
            updatedAt = Instant.ofEpochMilli(2000)
        }

        val flat = AppConfigMapper.toFlat(doc)

        assertThat(flat.appId).isEqualTo("app-1")
        assertThat(flat.appleBundleId).isEqualTo("com.example.app")
        assertThat(flat.appleIssuerId).isEqualTo("iss-1")
        assertThat(flat.applePrivateKey).isEqualTo("pk-1")
        assertThat(flat.googleClientIds.ios).isEqualTo("ios-client")
        assertThat(flat.productTierMap).containsEntry("prod.pro", "pro")
        assertThat(flat.iapEnv).isEqualTo("production") // 缺省
        assertThat(flat.configVersion).isEqualTo(3)
        assertThat(flat.createdAt).isEqualTo(Instant.ofEpochMilli(1000))
        assertThat(flat.updatedAt).isEqualTo(Instant.ofEpochMilli(2000))
    }

    @Test
    fun keepsExplicitIapEnv() {
        val doc = AppConfigDocument().apply { iap = IapConfig(env = "sandbox") }
        assertThat(AppConfigMapper.toFlat(doc).iapEnv).isEqualTo("sandbox")
    }
}
