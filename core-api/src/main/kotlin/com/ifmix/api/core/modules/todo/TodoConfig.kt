package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.redis.CacheAside
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.service.TodoItemService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate

@Configuration
class TodoConfig {

    @Bean
    fun todoRepository(clusterResolver: MongoClusterResolver): CRUDRepository<TodoEntity> =
        CRUDRepository(clusterResolver.primary(), TodoEntity::class.java)

    @Bean
    fun todoCrudService(todoRepository: CRUDRepository<TodoEntity>): CRUDService<TodoEntity> =
        CRUDService(todoRepository)

    @Bean
    fun todoService(todoCrudService: CRUDService<TodoEntity>, cacheAside: CacheAside): TodoService =
        TodoService(todoCrudService, cacheAside)

    // ---- TodoItem beans ----

    @Bean
    fun todoItemRepository(mongo: MongoTemplate): TodoItemRepository =
        TodoItemRepository(mongo)

    @Bean
    fun todoItemService(todoItemRepository: TodoItemRepository): TodoItemService =
        TodoItemService(todoItemRepository)
}
