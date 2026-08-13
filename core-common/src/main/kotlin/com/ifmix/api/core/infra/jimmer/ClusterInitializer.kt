package com.ifmix.api.core.infra.jimmer

import org.flywaydb.core.Flyway
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 启动时对 writer DataSource 执行 Flyway migrate。
 */
@Component
class ClusterInitializer(
    private val clusterRegistry: ClusterRegistry,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        Flyway.configure()
            .dataSource(clusterRegistry.writerDataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }
}
