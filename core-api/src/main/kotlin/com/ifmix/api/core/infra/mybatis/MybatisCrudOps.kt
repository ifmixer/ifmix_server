package com.ifmix.api.core.infra.mybatis

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.ifmix.api.core.infra.db.SvcCtx
import java.util.UUID
import kotlin.reflect.KProperty1

/**
 * MyBatis Plus 通用 CRUD 操作。
 *
 * 基于 BaseMapper + KProperty，不需要传函数引用。
 * 软删除由 @TableLogic 注解自动处理，ops 里不用管 deletedAt 条件。
 *
 * @param entityClass 实体类（用于 KtQueryWrapper 初始化）
 * @param appIdProp   appId 属性引用，用于多租户过滤
 * @param idProp      主键属性引用
 * @param mapperFn    从 SvcCtx 获取 BaseMapper 实例的函数
 */
class MybatisCrudOps<T : Any>(
    private val entityClass: Class<T>,
    private val appIdProp: KProperty1<T, UUID?>,
    private val idProp: KProperty1<T, UUID>,
    private val mapperFn: (SvcCtx) -> BaseMapper<T>,
) {

    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): T? {
        val wrapper = KtQueryWrapper<T>(entityClass)
        wrapper.eq(appIdProp, appIdVal)
        wrapper.eq(idProp, idVal)
        return mapperFn(ctx).selectOne(wrapper)
    }

    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val wrapper = KtQueryWrapper<T>(entityClass)
        wrapper.eq(appIdProp, appIdVal)
        val idList = ids.toList()
        wrapper.`in`(idProp, idList)
        return mapperFn(ctx).selectList(wrapper)
    }

    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int): List<T> {
        val wrapper = KtQueryWrapper<T>(entityClass)
        wrapper.eq(appIdProp, appIdVal)
        if (cursor != null) {
            wrapper.lt(idProp, cursor)
        }
        wrapper.orderBy(true, false, idProp)
        wrapper.last(true, "LIMIT $limit")
        return mapperFn(ctx).selectList(wrapper)
    }

    fun insert(ctx: SvcCtx, entity: T) {
        mapperFn(ctx).insert(entity)
    }

    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean =
        mapperFn(ctx).deleteById(idVal) > 0

    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean {
        val wrapper = KtQueryWrapper<T>(entityClass)
        wrapper.eq(appIdProp, appIdVal)
        wrapper.eq(idProp, idVal)
        return mapperFn(ctx).exists(wrapper)
    }
}
