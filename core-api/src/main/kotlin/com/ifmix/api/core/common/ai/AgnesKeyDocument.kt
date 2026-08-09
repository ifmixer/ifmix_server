package com.ifmix.api.core.common.ai

import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.db.AppScoped
import com.ifmix.api.core.common.db.SoftDeletable
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Agnes AI API key 文档。
 *
 * 存储可用的 AI 后端 key 及其配额、冷却、模型绑定等元数据。
 * 软删（deletedAt != null）的 key 不参与挑选。
 */
@Document(collection = "agnes_keys")
class AgnesKeyDocument : BaseDocument(), AppScoped, SoftDeletable {

    /** 所属应用 ID（分片键）。 */
    override var appId: String = ""

    /** 删除时间（null = 启用，非 null = 已软删）。 */
    override var deletedAt: Instant? = null

    /** API key（加密存储）。 */
    @Indexed(unique = true)
    var key: String? = null

    /** key 绑定的邮箱/负责人。 */
    var email: String? = null

    /** key 类型：PRIMARY / FALLBACK / HOTSPARE。 */
    var type: String? = null

    /** 每日配额上限（-1 = 无限制）。 */
    var rateLimit: Long = -1

    /** 配额窗口（秒），默认 86400（一天）。 */
    var windowSec: Long = 86_400L

    /** 支持的模型列表（逗号分隔）。 */
    var models: String? = null

    /** 不可用直到时间（冷却到期后自动恢复）。 */
    var unavailableUntil: Instant? = null
}
