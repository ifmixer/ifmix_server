package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId

/**
 * todo 业务逻辑：**组合**持有通用 CRUDAppService（不继承），委托通用 CRUD，只实现定制逻辑
 * （带内嵌 items 的创建、部分更新自动生成）。
 */
class TodoService(private val crud: CRUDService<TodoEntity>) {

    /** 创建 todo（内嵌 items 单文档原子写），返回新 id。 */
    fun create(ctx: RequestContext, req: CreateTodoRequest): String {
        val doc = TodoEntity().apply {
            title = req.title
            done = false
            items = (req.items ?: emptyList()).map {
                TodoItem(id = ObjectId().toHexString(), content = it.content, done = false)
            }.toMutableList()
        }
        return crud.createOne(ctx, doc)
    }

    /** 部分更新：直接把 patch 交给通用层自动生成 Mongo $set（非空字段），无需手写字段。 */
    fun update(ctx: RequestContext, id: String, patch: UpdateTodoRequest): Boolean =
        crud.updateById(ctx, id, patch)

    fun getById(ctx: RequestContext, id: String): TodoEntity = crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoEntity? = crud.findById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoEntity> =
        crud.findByCursor(ctx, input)

    fun deleteById(ctx: RequestContext, id: String): Boolean = crud.deleteById(ctx, id)
}
