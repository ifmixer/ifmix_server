package com.ifmix.core.api.infra.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream

@Configuration
class FcmConfig(
    @Value("\${app.noti.fcm.credentials-json:}")
    private val credentialsJson: String,
) {
    @Bean
    @ConditionalOnProperty(name = ["app.noti.fcm.enabled"], havingValue = "true")
    fun firebaseMessaging(): FirebaseMessaging {
        require(credentialsJson.isNotBlank()) {
            "app.noti.fcm.enabled=true requires FIREBASE_CREDENTIALS_JSON"
        }
        val app = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(
                        GoogleCredentials.fromStream(ByteArrayInputStream(credentialsJson.toByteArray(Charsets.UTF_8)))
                    )
                    .build()
            )
        return FirebaseMessaging.getInstance(app)
    }
}
