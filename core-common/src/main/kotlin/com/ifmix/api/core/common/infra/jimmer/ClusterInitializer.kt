package com.ifmix.api.core.common.infra.jimmer

import org.flywaydb.core.Flyway
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 启动时对 writer DataSource 执行 Flyway migrate。
 * 仅在 spring.flyway.enabled=true（默认）时生效；admin 服务设为 false 跳过。
 */
@Component
@ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "true", matchIfMissing = true)
class ClusterInitializer(
    private val clusterRegistry: ClusterRegistry,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        Flyway.configure()
            .dataSource(clusterRegistry.flywayDataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }
}
