package com.ifmix.api.core.modules.cms.service

import com.ifmix.api.core.dto.common.CreateOneRes
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.dto.cms.SubmitFeedbackReq
import com.ifmix.api.core.modules.cms.service.internal.FeedbackInternalService
import org.springframework.stereotype.Service

@Service
class CmsFacadeService(
    private val svcCtxFactory: SvcCtxFactory,
    private val internalService: FeedbackInternalService,
    private val tx: TxRunner,
) {
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc ->
            CreateOneRes(id = internalService.submit(sc, req))
        }
}
