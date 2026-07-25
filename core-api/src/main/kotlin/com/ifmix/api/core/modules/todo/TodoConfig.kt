package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** todo 模块 bean 装配。todos 集合开启软删。template 经集群路由接缝获取（未来多集群可换实现）。 */
@Configuration
class TodoConfig {

    @Bean
    fun todoRepository(clusterResolver: MongoClusterResolver): BaseAppRepository<TodoDocument> =
        BaseAppRepository(clusterResolver.primary(), TodoDocument::class.java, softDelete = true)

    @Bean
    fun todoService(todoRepository: BaseAppRepository<TodoDocument>): TodoService =
        TodoService(todoRepository)
}
