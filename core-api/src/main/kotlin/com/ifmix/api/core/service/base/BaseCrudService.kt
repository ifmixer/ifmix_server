package com.ifmix.api.core.service.base

import com.ifmix.api.core.infra.db.CursorQueryInput
import com.ifmix.api.core.infra.db.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
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
    open fun findById(id: UUID): E? =
        repo.findById(id)

    @Transactional(readOnly = true)
    open fun getById(id: UUID): E =
        repo.findById(id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional(readOnly = true)
    open fun <V : View<E>> findById(id: UUID, viewType: KClass<V>): V? =
        repo.findById(id, viewType)

    @Transactional(readOnly = true)
    open fun <V : View<E>> getById(id: UUID, viewType: KClass<V>): V =
        repo.findById(id, viewType) ?: throw ApiError(ErrorCode.NOT_FOUND)

    /**
     * 游标分页：委托 repository 的 SQL 分页实现。
     */
    @Transactional(readOnly = true)
    open fun findByCursor(input: CursorQueryInput = CursorQueryInput()): Page<E> =
        repo.findByCursor(input)

    @Transactional
    open fun create(input: Input<E>): E =
        repo.insert(input)

    @Transactional
    open fun update(input: Input<E>): E =
        repo.update(input)

    @Transactional
    open fun save(input: Input<E>): E =
        repo.save(input)

    @Transactional
    open fun deleteById(id: UUID) =
        repo.deleteById(id)
}

/**
 * 面向多租户实体的 Service。
 * 类型约束要求 E 实现 AppScopedProps。
 */
open class BaseAppCrudService<E : AppScopedProps>(
    repo: BaseAppCrudRepository<E>,
) : BaseCrudService<E>(repo)
