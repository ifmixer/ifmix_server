package com.ifmix.api.core.service.base

import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.entity.AppScopedProps
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.reflect.KClass
import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.repository.base.BaseAppCrudRepository

/**
 * 通用 Service 层。委托 BaseCrudRepository，标注事务。
 * 各模块 Service 继承后只需添加领域特有方法。
 */
open class BaseCrudService<E : Any>(
    protected val repo: BaseCrudRepository<E>,
) {
    @Transactional(readOnly = true)
    open fun findById(ctx: OperationContext, id: UUID): E? =
        repo.findById(ctx.repoCtx, id)

    @Transactional(readOnly = true)
    open fun getById(ctx: OperationContext, id: UUID): E =
        repo.findById(ctx.repoCtx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional(readOnly = true)
    open fun <V : View<E>> findById(ctx: OperationContext, id: UUID, viewType: KClass<V>): V? =
        repo.findById(ctx.repoCtx, id, viewType)

    @Transactional(readOnly = true)
    open fun <V : View<E>> getById(ctx: OperationContext, id: UUID, viewType: KClass<V>): V =
        repo.findById(ctx.repoCtx, id, viewType) ?: throw ApiError(ErrorCode.NOT_FOUND)

    /**
     * 游标分页：委托 repository 的 SQL 分页实现。
     */
    @Transactional(readOnly = true)
    open fun findByCursor(ctx: OperationContext, input: CursorQueryInput = CursorQueryInput()): Page<E> =
        repo.findByCursor(ctx.repoCtx, input)

    @Transactional
    open fun create(ctx: OperationContext, input: Input<E>): E =
        repo.insert(ctx.repoCtx, input)

    @Transactional
    open fun update(ctx: OperationContext, input: Input<E>): E =
        repo.update(ctx.repoCtx, input)

    @Transactional
    open fun save(ctx: OperationContext, input: Input<E>): E =
        repo.save(ctx.repoCtx, input)

    @Transactional
    open fun deleteById(ctx: OperationContext, id: UUID) =
        repo.deleteById(ctx.repoCtx, id)
}

/**
 * 面向多租户实体的 Service。
 * 类型约束要求 E 实现 AppScopedProps。
 */
open class BaseAppCrudService<E : AppScopedProps>(
    repo: BaseAppCrudRepository<E>,
) : BaseCrudService<E>(repo)
