package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CRUDAppRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.service.CRUDAppService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** todo 模块 bean 装配。todos 集合开启软删。template 经集群路由接缝获取（未来多集群可换实现）。 */
@Configuration
class TodoConfig {

    @Bean
    fun todoRepository(clusterResolver: MongoClusterResolver): CRUDAppRepository<TodoDocument> =
        CRUDAppRepository(clusterResolver.primary(), TodoDocument::class.java, softDelete = true)

    @Bean
    fun todoCrudService(todoRepository: CRUDAppRepository<TodoDocument>): CRUDAppService<TodoDocument> =
        CRUDAppService(todoRepository)

    @Bean
    fun todoService(todoCrudService: CRUDAppService<TodoDocument>): TodoService =
        TodoService(todoCrudService)
}
