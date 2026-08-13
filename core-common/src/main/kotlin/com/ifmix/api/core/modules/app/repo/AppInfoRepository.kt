package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.entity.appconfig.AppInfo
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.springframework.stereotype.Repository

@Repository
class AppInfoRepository(
    clusterRegistry: ClusterRegistry,
) : BaseCrudRepository<AppInfo>(clusterRegistry, AppInfo::class)
