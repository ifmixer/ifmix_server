package com.ifmix.api.core.common.jimmer.repository.appconfig

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.entity.appconfig.AppConfig
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

@Component
class AppConfigRepository(
    sql: KSqlClient,
) : BaseAppCrudRepository<AppConfig>(sql, AppConfig::class) {
}
