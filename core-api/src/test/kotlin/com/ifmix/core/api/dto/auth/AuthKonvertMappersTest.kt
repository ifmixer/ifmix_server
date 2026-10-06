package com.ifmix.core.api.dto.auth

import com.ifmix.core.api.modules.auth.handler.UserDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [AuthKonvertMappers] 生成实现（AuthKonvertMappersImpl）与原手写 AuthApiController.toWire 的
 * 逐字段等价断言（konvert-rollout-server.md K4 验收）：
 * - accessToken/refreshToken 同名直取；expiresIn Long → Int（LONG_TO_INT_CONVERTER）；
 * - user: UserDto → UserInfoRes 嵌套组合；module refreshExpiresAt 被 wire 类型忽略。
 */
class AuthKonvertMappersTest {

    @Test
    fun `loginRes maps all wire fields with long to int and nested user`() {
        val userId = UUID.randomUUID()
        val source = com.ifmix.core.api.modules.auth.handler.LoginRes(
            accessToken = "at",
            refreshToken = "rt",
            refreshExpiresAt = null,
            expiresIn = 3600L,
            user = UserDto(id = userId, email = "e@x.com"),
        )

        val res = AuthKonvertMappersImpl.toWire(source)

        assertEquals("at", res.accessToken)
        assertEquals("rt", res.refreshToken)
        assertEquals(3600, res.expiresIn)
        assertEquals(userId, res.user.id)
        assertEquals("e@x.com", res.user.email)
    }

    @Test
    fun `userDto maps id and null email`() {
        val userId = UUID.randomUUID()

        val res = AuthKonvertMappersImpl.toWire(UserDto(id = userId, email = null))

        assertEquals(userId, res.id)
        assertEquals(null, res.email)
    }
}
