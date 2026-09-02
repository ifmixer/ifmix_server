package com.ifmix.core.api.infra.jimmer

import com.ifmix.core.api.entity.common.MutableProps
import com.ifmix.core.api.entity.common.MutablePropsDraft
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.sql.DraftInterceptor
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 自动填充 createdAt / updatedAt。
 * 新建时若未设置则自动填充，更新时强制刷新 updatedAt。
 */
@Component
class TimestampDraftInterceptor : DraftInterceptor<MutableProps, MutablePropsDraft> {
    override fun beforeSave(draft: MutablePropsDraft, original: MutableProps?) {
        val now = Instant.now()
        if (original === null) {
            // INSERT
            if (!isLoaded(draft, MutableProps::createdAt)) {
                draft.createdAt = now
            }
        }
        // INSERT 或 UPDATE 都刷新 updatedAt
        draft.updatedAt = now
    }
}
