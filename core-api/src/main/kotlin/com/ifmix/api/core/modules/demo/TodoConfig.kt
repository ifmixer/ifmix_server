package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.service.TodoItemService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.ifmix.api.core.modules.demo.entity.TodoEntity
import com.ifmix.api.core.modules.demo.repo.TodoRepository

/**
 * Todo 模块 bean 装配。
 */
@Configuration
class TodoConfig {

    @Bean
    @ConditionalOnMissingBean(TodoRepository::class)
    fun todoRepository(clusterResolver: com.ifmix.api.core.common.db.MongoClusterResolver): TodoRepository {
        return TodoRepository()
    }

    // 注意：勿加 @ConditionalOnMissingBean(CRUDOps/CRUDService)——它们按擦除后的原始类型匹配，
    // 会与其他模块的同类 bean 冲突导致本模块 bean 被跳过。各模块各自定义自己的泛型 bean，
    // Spring 按泛型参数（TodoEntity）区分注入。
    @Bean
    fun todoCrudRepository(
        clusterResolver: com.ifmix.api.core.common.db.MongoClusterResolver,
    ): com.ifmix.api.core.common.db.CRUDOps<TodoEntity> {
        return com.ifmix.api.core.common.db.CRUDOps(clusterResolver.primary(), TodoEntity::class.java)
    }

    @Bean
    fun todoCrudService(repo: com.ifmix.api.core.common.db.CRUDOps<TodoEntity>): CRUDService<TodoEntity> =
        CRUDService(repo)

    @Bean
    @ConditionalOnMissingBean(TodoService::class)
    fun todoService(todoCrudService: CRUDService<TodoEntity>, cacheAside: com.ifmix.api.core.common.redis.CacheAside): TodoService =
        TodoService(todoCrudService, cacheAside)

    // ---- TodoItem beans ----

    @Bean
    @ConditionalOnMissingBean(TodoItemRepository::class)
    fun todoItemRepository(mongo: org.springframework.data.mongodb.core.MongoTemplate): TodoItemRepository =
        TodoItemRepository(mongo)

    @Bean
    @ConditionalOnMissingBean(TodoItemService::class)
    fun todoItemService(todoItemRepository: TodoItemRepository): TodoItemService =
        TodoItemService(todoItemRepository)
}
