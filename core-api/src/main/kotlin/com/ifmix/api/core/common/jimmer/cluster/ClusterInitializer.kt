package com.ifmix.api.core.common.jimmer.cluster

import org.flywaydb.core.Flyway
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 启动时对所有集群的 writer DataSource 执行 Flyway migrate。
 */
@Component
class ClusterInitializer(
    private val clusterRegistry: ClusterRegistry,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        clusterRegistry.allWriterDataSources().forEach { (name, ds) ->
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate()
        }
    }
}
