package com.ifmix.api.core.repository.appconfig

import com.ifmix.api.core.repository.base.BaseAppCrudRepository
import com.ifmix.api.core.entity.appconfig.AppConfig
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

@Component
class AppConfigRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppConfig>(sql, AppConfig::class) {
}
