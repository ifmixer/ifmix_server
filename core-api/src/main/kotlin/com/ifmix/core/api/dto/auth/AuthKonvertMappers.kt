package com.ifmix.core.api.dto.auth

import com.ifmix.core.api.modules.auth.handler.UserDto
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping
import io.mcarle.konvert.api.converter.LONG_TO_INT_CONVERTER

/**
 * auth 模块出参 Konvert mapper（konvert-rollout-server.md §4 + §0.5 语法校准）。
 *
 * 生成实现为 object [AuthKonvertMappersImpl]（同包，KSP 产物）。
 *
 * ⚠️ 同名类型：module `LoginRes`（com.ifmix.core.api.modules.auth.handler）与 wire
 * [LoginRes]（dto.auth）同名 —— 前者参数类型写全限定名。
 *
 * ⚠️ 数字类型转换默认禁用（BaseTypeConverter.enabledByDefault=false，§0.5 第 9 条）：
 * expiresIn 的 Long → Int 需 `Mapping(enable = [LONG_TO_INT_CONVERTER])` 显式启用。
 *
 * 映射表（预期，编译后由 [AuthKonvertMappersTest] 断言）：
 * - accessToken/refreshToken 同名直取；
 * - expiresIn Long → Int（LONG_TO_INT_CONVERTER，生成 `.toInt()`）；
 * - module `refreshExpiresAt` wire 无对应字段，忽略；
 * - user: [UserDto] → [UserInfoRes] 嵌套经下方 toWire 自动组合。
 *
 * `MeRes` 组装（字段改名 active→tierActive + expiresAt Long→Instant）含非纯搬运语义，
 * 按文档 §4 明确回退路径保留手写（AuthApiController.me 原样）。
 */
@Konverter
interface AuthKonvertMappers {

    fun toWire(source: UserDto): UserInfoRes

    fun toWire(source: com.ifmix.core.api.modules.auth.handler.LoginRes): LoginRes
}
