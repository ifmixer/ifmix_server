package com.ifmix.api.core.common.jimmer.repository.appconfig

import com.ifmix.api.core.common.jimmer.base.BaseCrudRepository
import com.ifmix.api.core.common.jimmer.entity.appconfig.AppInfo
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component

@Component
class AppInfoRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AppInfo>(sql, AppInfo::class) {
}
