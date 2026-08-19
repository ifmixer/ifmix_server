package com.ifmix.api.core.modules.demo

import com.ifmix.api.core.common.db.CRUDOps
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.http.RepoCtx
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.modules.demo.repo.TodoItemRepository
import com.ifmix.api.core.modules.demo.service.TodoItemService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate
import com.ifmix.api.core.modules.demo.entity.TodoEntity

@Configuration
class TodoConfig {

    @Bean
    fun todoCrudRepository(clusterResolver: MongoClusterResolver): CRUDOps<TodoEntity> =
        CRUDOps(clusterResolver.primary(), TodoEntity::class.java)

    @Bean
    @ConditionalOnMissingBean(TodoService::class)
    fun todoService(
        todoCrudRepository: CRUDOps<TodoEntity>,
        cacheAside: CacheAside,
    ): TodoService = TodoService(todoCrudRepository, cacheAside)

    @Bean
    @ConditionalOnMissingBean(TodoItemRepository::class)
    fun todoItemRepository(mongo: MongoTemplate): TodoItemRepository =
        TodoItemRepository(mongo)

    @Bean
    @ConditionalOnMissingBean(TodoItemService::class)
    fun todoItemService(todoItemRepository: TodoItemRepository): TodoItemService =
        TodoItemService(todoItemRepository)

    @Bean
    @ConditionalOnMissingBean(DemoFacade::class)
    fun demoFacade(
        todoService: TodoService,
        todoItemService: TodoItemService,
    ): DemoFacade = DemoFacade(todoService, todoItemService)
}
