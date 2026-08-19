package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.service.CRUDService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** todo 模块 bean 装配。todos 集合开启软删（由 BaseAppEntity 能力接口声明，CRUDRepository 反射探测）。 */
@Configuration
class TodoConfig {

    @Bean
    fun todoRepository(clusterResolver: MongoClusterResolver): CRUDRepository<TodoEntity> =
        CRUDRepository(clusterResolver.primary(), TodoEntity::class.java)

    @Bean
    fun todoCrudService(todoRepository: CRUDRepository<TodoEntity>): CRUDService<TodoEntity> =
        CRUDService(todoRepository)

    @Bean
    fun todoService(todoCrudService: CRUDService<TodoEntity>): TodoService =
        TodoService(todoCrudService)
}
