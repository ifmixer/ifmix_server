package com.ifmix.api.core.modules.customer

import com.ifmix.api.core.entity.customer.Customer
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.customer.repo.CustomerRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CustomerFacade(
    private val mcFactory: ModuleCtxFactory,
    private val customerRepo: CustomerRepository,
) {
    fun findById(ctx: OperationContext, id: UUID): Customer? =
        customerRepo.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun createCustomer(ctx: OperationContext): UUID =
        customerRepo.createCustomer(mcFactory.forApp(ctx), ctx.mustGetAppId())

    fun exists(ctx: OperationContext, id: UUID): Boolean =
        customerRepo.exists(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)
}
