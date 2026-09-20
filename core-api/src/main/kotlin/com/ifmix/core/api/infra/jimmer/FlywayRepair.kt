package com.ifmix.core.api.infra.jimmer

import org.flywaydb.core.Flyway

/**
 * 独立 Flyway repair 入口，由 Gradle `flywayRepair` task 调用。
 *
 * 用途：重算并修正 flyway_schema_history 中的 checksum，使之与当前脚本文件一致。
 * 典型场景：某条已应用记录的 checksum 与脚本对不上（如本地 baseline 当初 checksum 为空），
 * 导致后续 migrate 校验失败——repair 后再 migrate 即可。
 * repair 不改数据库表结构，只修历史表元数据。
 *
 * 连接配置同 [FlywayMigrate]（环境变量 DB_URL/DB_USER/DB_PASSWORD，带本地默认值）。
 * 用法：./gradlew :core-api:flywayRepair
 * 覆盖库：DB_URL=... DB_USER=... DB_PASSWORD=... ./gradlew :core-api:flywayRepair
 */
object FlywayRepair {
    @JvmStatic
    fun main(args: Array<String>) {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/core_api_local"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        println("[flywayRepair] target = $url (user=$user)")
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .repair()
        println("[flywayRepair] done (flyway_schema_history checksums realigned to scripts)")
    }
}
