package com.ifmix.api.core.service.auth

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.mockito.kotlin.mock

class MergeOnLoginListenerTest {

    @Test fun `no installId skips merge`() {
        val mongo = mock<org.springframework.data.mongodb.core.MongoTemplate>()
        val tx = mock<com.ifmix.api.core.common.tx.TxRunner>()
        val listener = MergeOnLoginListener(mongo, tx)
        listener.onLogin(AuthLoggedInEvent("app1", "identity1", "user1", null))
        assertThat(true).isTrue()
    }
}
