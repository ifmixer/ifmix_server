package com.ifmix.api.core.modules.demo.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.demo.entity.TodoItemEntity
import com.ifmix.api.core.modules.demo.handler.TodoItemEntityHandler
import org.springframework.stereotype.Component

/**
 * TodoItem 业务服务（适配层）。保留公共 API 兼容 DataLoader。
 * 内部委托给 TodoItemEntityHandler。
 */
@Component
class TodoItemService(private val handler: TodoItemEntityHandler) {

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemEntity> =
        handler.findByTodoIds(ctx.appId, todoIds)
}
