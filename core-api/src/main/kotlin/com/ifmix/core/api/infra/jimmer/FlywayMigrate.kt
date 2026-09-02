package com.ifmix.core.api.infra.jimmer

import org.flywaydb.core.Flyway

/**
 * 独立 Flyway migrate 入口，由 Gradle `flywayMigrate` task 调用（不随 app 启动执行）。
 *
 * 连接配置来自环境变量（带本地默认值）：
 * - DB_URL      (默认 jdbc:postgresql://localhost:5432/core_api_local)
 * - DB_USER     (默认 postgres)
 * - DB_PASSWORD (默认 postgres)
 *
 * 用法：./gradlew :core-api:flywayMigrate
 * 覆盖库：DB_URL=... DB_USER=... DB_PASSWORD=... ./gradlew :core-api:flywayMigrate
 */
object FlywayMigrate {
    @JvmStatic
    fun main(args: Array<String>) {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/core_api_local"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        println("[flywayMigrate] target = $url (user=$user)")
        val result = Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
        println("[flywayMigrate] success=${result.success} migrationsExecuted=${result.migrationsExecuted} targetSchemaVersion=${result.targetSchemaVersion}")
    }
}
