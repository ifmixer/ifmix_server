package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import com.ifmix.api.core.entity.auth.AuthTenant
import org.springframework.stereotype.Repository

/** AuthTenant repository */
@Repository
class AuthTenantRepository(
    clusterRegistry: ClusterRegistry,
) : BaseCrudRepository<AuthTenant>(clusterRegistry, AuthTenant::class)
