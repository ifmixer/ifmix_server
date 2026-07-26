package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.BaseAppService
import org.bson.types.ObjectId

/** todo 业务逻辑：继承通用 CRUD，仅定制带内嵌 items 的创建与 patch 更新。 */
class TodoService(repo: BaseAppRepository<TodoDocument>) : BaseAppService<TodoDocument>(repo) {

    /** 创建 todo（内嵌 items 单文档原子写），返回新 id。 */
    fun create(ctx: RequestContext, req: CreateTodoRequest): String {
        val doc = TodoDocument().apply {
            title = req.title
            done = false
            items = (req.items ?: emptyList()).map {
                TodoItem(id = ObjectId().toHexString(), content = it.content, done = false)
            }.toMutableList()
        }
        return createOne(ctx, doc) // 继承自 BaseAppService：盖章 appId/时间戳 + 插入
    }

    /** 部分更新：仅设置提供的字段；空 patch 时校验存在性后视为命中。 */
    fun update(ctx: RequestContext, id: String, patch: UpdateTodoRequest): Boolean {
        val set = mutableMapOf<String, Any?>()
        patch.title?.let { set["title"] = it }
        patch.done?.let { set["done"] = it }
        if (set.isEmpty()) {
            getById(ctx, id) // 不存在则抛 NOT_FOUND
            return true
        }
        return updateById(ctx, id, set)
    }
}
