package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.repo.BaseCrudRepository
import com.ifmix.api.core.entity.appconfig.AppInfo
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Repository

@Repository
class AppInfoRepository(sql: KSqlClient,) : BaseCrudRepository<AppInfo>(sql, AppInfo::class)
