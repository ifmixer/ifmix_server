package com.ifmix.core.api.bff.api.customer.media

import com.ifmix.core.api.infra.http.ActionSpec
import com.ifmix.core.api.infra.http.ActorRequirement

/**
 * media 模块 2 个 API action 的 [ActionSpec] 集中定义。
 *
 * 唯一依据是原 [com.ifmix.core.api.bff.graphql.customer.media.StorageFetcher] 的
 * `ctxProvider.fromDfe(dfe)` 实参——全部默认值：requireAppId=true、requireActorType=ACTOR_CUSTOMER、
 * requireLocale/Country/Currency=false → actor=CUSTOMER、requireProjectId=true。
 */
object MediaSpecs {
    val PRESIGN_UPLOAD = ActionSpec("m_media_media_presignUpload", isMutation = true, actor = ActorRequirement.CUSTOMER)
    val PRESIGN_DOWNLOAD = ActionSpec("m_media_media_presignDownload", isMutation = true, actor = ActorRequirement.CUSTOMER)
}
