package com.ifmix.api.core.repository.appconfig

import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.entity.appconfig.AppInfo
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

@Component
class AppInfoRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AppInfo>(sql, AppInfo::class) {
}
