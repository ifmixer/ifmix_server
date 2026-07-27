package com.ifmix.api.core.common.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.doesNotContain
import org.junit.jupiter.api.Test

class HashingTest {

    @Test
    fun `sha256Base64Url is deterministic`() {
        val a = Hashing.sha256Base64Url("hello")
        val b = Hashing.sha256Base64Url("hello")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `sha256Base64Url different inputs produce different outputs`() {
        assertThat(Hashing.sha256Base64Url("a")).isNotEqualTo(Hashing.sha256Base64Url("b"))
    }

    @Test
    fun `sha256Base64Url is base64url lowercase`() {
        val hash = Hashing.sha256Base64Url("test")
        // base64url alphabet: [a-z0-9\-_] (no padding)
        assertThat(hash.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }).isTrue()
        assertThat(hash).doesNotContain("=")
    }

    @Test
    fun `randomTokenBase64Url produces unique tokens`() {
        val a = Hashing.randomTokenBase64Url()
        val b = Hashing.randomTokenBase64Url()
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `randomTokenBase64Url is base64url format`() {
        val token = Hashing.randomTokenBase64Url()
        assertThat(token.length).isEqualTo(43) // 32 bytes → ceil(32*4/3) chars, no padding
    }
}
