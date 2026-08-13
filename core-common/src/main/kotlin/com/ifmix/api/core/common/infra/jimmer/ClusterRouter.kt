package com.ifmix.api.core.common.infra.jimmer

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ClusterRouter(private val props: AppDataSourceProperties) {
    fun resolveCluster(appId: UUID?): String = props.routing.defaultCluster
    fun resolveGlobalCluster(): String = props.routing.globalCluster
}
