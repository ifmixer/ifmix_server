package com.ifmix.api.core.entity

import java.util.UUID

/**
 * 所有多租户实体的标记接口。
 * AppScopedFilter 全局过滤器会对包含 appId 属性的实体自动注入 WHERE app_id = ?。
 */
interface AppScopedProps {
    val appId: UUID
}
