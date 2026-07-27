package com.ifmix.api.core.common.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class EmailNormalizeTest {

    @Test
    fun `normalizeEmail lowercases and trims`() {
        assertThat(EmailNormalize.normalizeEmail("  User@Example.COM  ")).isEqualTo("user@example.com")
    }

    @Test
    fun `normalizeEmail strips Google plus-tag`() {
        assertThat(EmailNormalize.normalizeEmail("user+tag@gmail.com")).isEqualTo("user@gmail.com")
        assertThat(EmailNormalize.normalizeEmail("USER+123@GMAIL.COM")).isEqualTo("user@gmail.com")
    }

    @Test
    fun `normalizeEmail no-plus domain untouched`() {
        assertThat(EmailNormalize.normalizeEmail("user@outlook.com")).isEqualTo("user@outlook.com")
    }

    @Test
    fun `normalizeEmail null returns null`() {
        assertThat(EmailNormalize.normalizeEmail(null)).isNull()
    }

    @Test
    fun `normalizeEmail empty returns empty`() {
        assertThat(EmailNormalize.normalizeEmail("")).isEqualTo("")
    }

    @Test
    fun `normalizePhone strips non-digits`() {
        assertThat(EmailNormalize.normalizePhone("+86 138-0000-1234")).isEqualTo("8613800001234")
    }

    @Test
    fun `normalizePhone null returns null`() {
        assertThat(EmailNormalize.normalizePhone(null)).isNull()
    }
}
